package com.pipezfacades.mixin;

import com.pipezfacades.FacadeShapes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Shape hooks into pipez's {@code PipeBlock} — the one thing plain Forge events cannot do.
 *
 * <p>Targets use production SRG names directly ({@code remap = false}) because pipez ships
 * reobfuscated; the names below were verified with {@code javap} against
 * {@code pipez-forge-1.20.1-1.2.26.jar}:
 * <ul>
 *     <li>{@code m_5940_} = {@code getShape(BlockState, BlockGetter, BlockPos, CollisionContext)}
 *         — outline / crosshair-raytrace shape;</li>
 *     <li>{@code m_5939_} = {@code getCollisionShape(BlockState, BlockGetter, BlockPos, CollisionContext)}.</li>
 * </ul>
 * (In a deobfuscated dev environment these targets will not match — this mixin is production-oriented.)
 */
@Mixin(targets = "de.maxhenkel.pipez.blocks.PipeBlock", remap = false)
public abstract class PipeBlockShapeMixin {

    private static final String GET_SHAPE = "m_5940_(Lnet/minecraft/world/level/block/state/BlockState;" +
            "Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;" +
            "Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;";

    private static final String GET_COLLISION_SHAPE = "m_5939_(Lnet/minecraft/world/level/block/state/BlockState;" +
            "Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;" +
            "Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;";

    /**
     * GregTech parity: while the player aims with a facade item (or sneaks empty-handed at a facaded
     * pipe), the pipe raytraces as a full cube so the cover grid is reachable across the whole face.
     */
    @Inject(method = GET_SHAPE, at = @At("HEAD"), cancellable = true)
    private void pipezfacades$fullCubeForFacadeAiming(BlockState state, BlockGetter level, BlockPos pos,
                                                      CollisionContext context,
                                                      CallbackInfoReturnable<VoxelShape> cir) {
        if (FacadeShapes.wantsFullCubeTargeting(level, pos, context)) {
            cir.setReturnValue(Shapes.block());
        }
    }

    /** Installed facade plates are part of the outline shape (visible box, clickable for removal). */
    @Inject(method = GET_SHAPE, at = @At("RETURN"), cancellable = true)
    private void pipezfacades$outlineWithPlates(BlockState state, BlockGetter level, BlockPos pos,
                                                CollisionContext context,
                                                CallbackInfoReturnable<VoxelShape> cir) {
        int mask = FacadeShapes.sidesMask(level, pos);
        if (mask != 0) {
            cir.setReturnValue(FacadeShapes.appendPlates(cir.getReturnValue(), mask));
        }
    }

    /** Facade plates are solid: entities stand on them instead of sinking through to the pipe. */
    @Inject(method = GET_COLLISION_SHAPE, at = @At("RETURN"), cancellable = true)
    private void pipezfacades$collideWithPlates(BlockState state, BlockGetter level, BlockPos pos,
                                                CollisionContext context,
                                                CallbackInfoReturnable<VoxelShape> cir) {
        int mask = FacadeShapes.sidesMask(level, pos);
        if (mask != 0) {
            cir.setReturnValue(FacadeShapes.appendPlates(cir.getReturnValue(), mask));
        }
    }
}
