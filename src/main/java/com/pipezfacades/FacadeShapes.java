package com.pipezfacades;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Shape helpers used by the {@code PipeBlockShapeMixin}:
 * <ul>
 *     <li>{@link #wantsFullCubeTargeting} — GregTech's trick: while the player aims with a facade item,
 *         the pipe's outline/raytrace shape becomes a full cube, so the cover grid is reachable across
 *         the whole face (otherwise the ray slips past the thin pipe arms near the face edges);</li>
 *     <li>{@link #sidesMask}/{@link #appendPlates} — 1/16-thick collision & outline plates for installed
 *         facades, so players stand on facades instead of sinking into them.</li>
 * </ul>
 */
public final class FacadeShapes {

    private static final VoxelShape[] PLATES = new VoxelShape[6];

    static {
        final double t = 1.0 / 16.0;
        PLATES[Direction.DOWN.ordinal()] = Shapes.box(0, 0, 0, 1, t, 1);
        PLATES[Direction.UP.ordinal()] = Shapes.box(0, 1 - t, 0, 1, 1, 1);
        PLATES[Direction.NORTH.ordinal()] = Shapes.box(0, 0, 0, 1, 1, t);
        PLATES[Direction.SOUTH.ordinal()] = Shapes.box(0, 0, 1 - t, 1, 1, 1);
        PLATES[Direction.WEST.ordinal()] = Shapes.box(0, 0, 0, t, 1, 1);
        PLATES[Direction.EAST.ordinal()] = Shapes.box(1 - t, 0, 0, 1, 1, 1);
    }

    private FacadeShapes() {
    }

    /** Bitmask (1 << side ordinal) of sides with an installed facade, from either logical side. */
    public static int sidesMask(BlockGetter getter, BlockPos pos) {
        if (!(getter instanceof Level level)) {
            return 0; // worldgen / pathfinding region wrappers — no facade info available
        }
        int mask = 0;
        if (level.isClientSide()) {
            BlockState[] sides = ClientFacadeStore.getSides(pos);
            if (sides == null) {
                return 0;
            }
            for (int i = 0; i < 6; i++) {
                if (sides[i] != null) {
                    mask |= 1 << i;
                }
            }
        } else if (level instanceof ServerLevel serverLevel) {
            FacadeSavedData.Entry[] sides = FacadeSavedData.get(serverLevel).getSides(pos);
            if (sides == null) {
                return 0;
            }
            for (int i = 0; i < 6; i++) {
                if (sides[i] != null) {
                    mask |= 1 << i;
                }
            }
        }
        return mask;
    }

    public static VoxelShape appendPlates(VoxelShape base, int mask) {
        VoxelShape shape = base;
        for (int i = 0; i < 6; i++) {
            if ((mask & (1 << i)) != 0) {
                shape = Shapes.or(shape, PLATES[i]);
            }
        }
        return shape;
    }

    /**
     * True while the aiming player should see the pipe as a full cube (GT cover-grid parity): holding a
     * placeable facade source, or sneaking with an empty hand at a pipe that has facades (removal aiming).
     */
    public static boolean wantsFullCubeTargeting(BlockGetter getter, BlockPos pos, CollisionContext context) {
        if (!(context instanceof EntityCollisionContext entityContext) ||
                !(entityContext.getEntity() instanceof Player player)) {
            return false;
        }
        ItemStack held = player.getMainHandItem();
        if (PipeUtil.resolveFacade(held) != null && PipeUtil.canPlaceNow(player, held)) {
            return true;
        }
        return held.isEmpty() && player.isShiftKeyDown() && sidesMask(getter, pos) != 0;
    }
}
