package com.pipezfacades.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;

import java.util.List;

/**
 * Draws a facade as a thin plate (1/16 block) flush with the chosen side of the pipe, textured with the
 * camouflage block's own sprites — the same visual GregTech covers use. Pure vertex emission, no baked
 * model juggling, so it works with any full block.
 */
public final class FacadePlateRenderer {

    /** Plate thickness (towards the pipe) and outer inset (avoids z-fighting with neighbouring blocks). */
    private static final float IN = 1f / 16f;
    private static final float OUT = 0.001f;

    private FacadePlateRenderer() {
    }

    /** Renders one plate. The pose stack must already be translated to the pipe's position. */
    public static void render(PoseStack poseStack, MultiBufferSource buffers, Level level, BlockPos pos,
                              Direction side, BlockState facade, RandomSource rand) {
        BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(facade);
        VertexConsumer vc = buffers.getBuffer(RenderType.cutoutMipped());

        // plate bounds inside the unit cube
        float x0 = 0, y0 = 0, z0 = 0, x1 = 1, y1 = 1, z1 = 1;
        switch (side) {
            case NORTH -> { z0 = OUT; z1 = IN; }
            case SOUTH -> { z0 = 1 - IN; z1 = 1 - OUT; }
            case WEST -> { x0 = OUT; x1 = IN; }
            case EAST -> { x0 = 1 - IN; x1 = 1 - OUT; }
            case DOWN -> { y0 = OUT; y1 = IN; }
            case UP -> { y0 = 1 - IN; y1 = 1 - OUT; }
        }

        int light = maxLight(
                LevelRenderer.getLightColor(level, pos),
                LevelRenderer.getLightColor(level, pos.relative(side)));

        for (Direction d : Direction.values()) {
            TextureAtlasSprite sprite = sprite(model, facade, d, rand);
            float shade = level.getShade(d, true);
            emitFace(vc, poseStack, d, x0, y0, z0, x1, y1, z1, sprite, shade, light);
        }
    }

    private static int maxLight(int a, int b) {
        return LightTexture.pack(
                Math.max(LightTexture.block(a), LightTexture.block(b)),
                Math.max(LightTexture.sky(a), LightTexture.sky(b)));
    }

    private static TextureAtlasSprite sprite(BakedModel model, BlockState state, Direction d, RandomSource rand) {
        List<net.minecraft.client.renderer.block.model.BakedQuad> quads = model.getQuads(state, d, rand);
        if (!quads.isEmpty()) {
            return quads.get(0).getSprite();
        }
        quads = model.getQuads(state, null, rand);
        if (!quads.isEmpty()) {
            return quads.get(0).getSprite();
        }
        return model.getParticleIcon(ModelData.EMPTY);
    }

    /**
     * Emits one axis-aligned quad of the plate box, CCW as seen from outside, with block-atlas UVs derived
     * from world coordinates (so full faces show the full texture and thin rims show matching 1px strips).
     */
    private static void emitFace(VertexConsumer vc, PoseStack poseStack, Direction d,
                                 float x0, float y0, float z0, float x1, float y1, float z1,
                                 TextureAtlasSprite sprite, float shade, int light) {
        float[][] v = switch (d) {
            case UP -> new float[][] { { x0, y1, z0 }, { x0, y1, z1 }, { x1, y1, z1 }, { x1, y1, z0 } };
            case DOWN -> new float[][] { { x0, y0, z1 }, { x0, y0, z0 }, { x1, y0, z0 }, { x1, y0, z1 } };
            case NORTH -> new float[][] { { x1, y1, z0 }, { x1, y0, z0 }, { x0, y0, z0 }, { x0, y1, z0 } };
            case SOUTH -> new float[][] { { x0, y1, z1 }, { x0, y0, z1 }, { x1, y0, z1 }, { x1, y1, z1 } };
            case WEST -> new float[][] { { x0, y1, z0 }, { x0, y0, z0 }, { x0, y0, z1 }, { x0, y1, z1 } };
            case EAST -> new float[][] { { x1, y1, z1 }, { x1, y0, z1 }, { x1, y0, z0 }, { x1, y1, z0 } };
        };
        var pose = poseStack.last();
        for (float[] p : v) {
            float u16;
            float v16;
            switch (d) {
                case UP, DOWN -> { u16 = p[0] * 16f; v16 = p[2] * 16f; }
                case NORTH -> { u16 = 16f - p[0] * 16f; v16 = 16f - p[1] * 16f; }
                case SOUTH -> { u16 = p[0] * 16f; v16 = 16f - p[1] * 16f; }
                case WEST -> { u16 = p[2] * 16f; v16 = 16f - p[1] * 16f; }
                default -> { u16 = 16f - p[2] * 16f; v16 = 16f - p[1] * 16f; } // EAST
            }
            vc.vertex(pose.pose(), p[0], p[1], p[2])
                    .color(shade, shade, shade, 1f)
                    .uv(sprite.getU(u16), sprite.getV(v16))
                    .uv2(light)
                    .normal(pose.normal(), d.getStepX(), d.getStepY(), d.getStepZ())
                    .endVertex();
        }
    }
}
