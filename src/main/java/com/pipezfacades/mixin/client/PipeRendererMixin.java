package com.pipezfacades.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.pipezfacades.ClientFacadeStore;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Hides pipez's extraction nozzle on sides covered by a facade.
 *
 * <p>The nozzle is drawn by pipez's block-entity renderer 0.001 OUTSIDE the block boundary
 * (PipeRenderer translates by step*0.001 before rotating), which puts it right in front of the facade's
 * camouflage face — floating and z-fighting with it at distance. GregTech has no equivalent overlay:
 * covers fully hide the pipe's guts, so we do the same.
 *
 * <p>Targets pipez's own (non-vanilla-override) methods, whose names and descriptors survive
 * reobfuscation; verified with javap against pipez-forge-1.20.1-1.2.26.jar:
 * {@code render(PipeTileEntity, float, PoseStack, MultiBufferSource, int, int)} and
 * {@code renderExtractor(Direction, PoseStack, VertexConsumer, List, int, int)}.
 * The renderer runs only on the render thread, so a static position capture is safe.
 */
@Mixin(targets = "de.maxhenkel.pipez.blocks.tileentity.render.PipeRenderer", remap = false)
public abstract class PipeRendererMixin {

    @Unique
    private static BlockPos pipezfacades$renderingPos;

    @Inject(method = "render(Lde/maxhenkel/pipez/blocks/tileentity/PipeTileEntity;F" +
            "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At("HEAD"))
    private void pipezfacades$capturePos(@Coerce BlockEntity blockEntity, float partialTicks,
                                         PoseStack stack, MultiBufferSource buffer, int light, int overlay,
                                         CallbackInfo ci) {
        pipezfacades$renderingPos = blockEntity.getBlockPos();
    }

    @Inject(method = "renderExtractor(Lnet/minecraft/core/Direction;Lcom/mojang/blaze3d/vertex/PoseStack;" +
            "Lcom/mojang/blaze3d/vertex/VertexConsumer;Ljava/util/List;II)V",
            at = @At("HEAD"), cancellable = true)
    private void pipezfacades$hideNozzleBehindFacade(Direction direction, PoseStack stack,
                                                     VertexConsumer consumer, List<BakedQuad> quads,
                                                     int light, int overlay, CallbackInfo ci) {
        BlockPos pos = pipezfacades$renderingPos;
        if (pos != null && ClientFacadeStore.get(pos, direction) != null) {
            ci.cancel();
        }
    }
}
