package com.pipezfacades.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Draws a facade as a thin plate (1/16 block) flush with the chosen side of the pipe, textured with the
 * camouflage block's own sprites.
 *
 * <p>The plate is assembled into an ad-hoc {@link BakedModel} and rendered through vanilla's
 * {@code ModelBlockRenderer} — the exact code path chunk geometry uses — so it receives real ambient
 * occlusion, smooth lighting, per-face shading and neighbour-based face culling. This makes the facade's
 * tone match GregTech's chunk-baked facades instead of looking uniformly (over)lit.
 *
 * <p>Where two facades meet along an edge, the plate belonging to the higher-ordinal side is trimmed back
 * by the other plate's thickness, so adjacent plates tile cleanly instead of overlapping and z-fighting.
 */
public final class FacadePlateRenderer {

    /** Plate thickness (towards the pipe) and outer inset (avoids z-fighting with neighbouring blocks). */
    private static final float IN = 1f / 16f;
    private static final float OUT = 0.001f;

    private FacadePlateRenderer() {
    }

    /**
     * Renders one plate. The pose stack must already be translated to the pipe's position.
     *
     * @param allSides all facade states on this pipe (index = direction ordinal) — used for edge trimming
     */
    public static void render(PoseStack poseStack, MultiBufferSource buffers, Level level, BlockPos pos,
                              Direction side, BlockState facade, BlockState[] allSides, RandomSource rand) {
        Minecraft mc = Minecraft.getInstance();
        BakedModel blockModel = mc.getBlockRenderer().getBlockModel(facade);
        PlateModel plate = buildPlate(blockModel, facade, plateBox(side, allSides), rand);

        RenderType renderType = ItemBlockRenderTypes.getChunkRenderType(facade);
        VertexConsumer vc = buffers.getBuffer(renderType);
        mc.getBlockRenderer().getModelRenderer().tesselateBlock(
                level, plate, facade, pos, poseStack, vc, true, rand, facade.getSeed(pos),
                OverlayTexture.NO_OVERLAY, ModelData.EMPTY, renderType);
    }

    /**
     * Plate bounds inside the unit cube, trimmed back wherever a perpendicular side with a LOWER direction
     * ordinal also has a facade — that plate keeps the shared edge, this one yields. Deterministic on both
     * sides of every pair, so plates tile with no overlap and no gap.
     */
    private static float[] plateBox(Direction side, BlockState[] sides) {
        float x0 = 0, y0 = 0, z0 = 0, x1 = 1, y1 = 1, z1 = 1;
        switch (side) {
            case NORTH -> { z0 = OUT; z1 = IN; }
            case SOUTH -> { z0 = 1 - IN; z1 = 1 - OUT; }
            case WEST -> { x0 = OUT; x1 = IN; }
            case EAST -> { x0 = 1 - IN; x1 = 1 - OUT; }
            case DOWN -> { y0 = OUT; y1 = IN; }
            case UP -> { y0 = 1 - IN; y1 = 1 - OUT; }
        }
        float[] b = { x0, y0, z0, x1, y1, z1 };
        for (Direction p : Direction.values()) {
            if (p.getAxis() == side.getAxis() || p.ordinal() >= side.ordinal() || sides[p.ordinal()] == null) {
                continue;
            }
            switch (p) {
                case DOWN -> b[1] = Math.max(b[1], IN);
                case UP -> b[4] = Math.min(b[4], 1 - IN);
                case NORTH -> b[2] = Math.max(b[2], IN);
                case SOUTH -> b[5] = Math.min(b[5], 1 - IN);
                case WEST -> b[0] = Math.max(b[0], IN);
                case EAST -> b[3] = Math.min(b[3], 1 - IN);
            }
        }
        return b;
    }

    private static PlateModel buildPlate(BakedModel blockModel, BlockState facade, float[] b, RandomSource rand) {
        float x0 = b[0], y0 = b[1], z0 = b[2], x1 = b[3], y1 = b[4], z1 = b[5];
        Map<Direction, List<BakedQuad>> quads = new EnumMap<>(Direction.class);
        for (Direction d : Direction.values()) {
            TextureAtlasSprite sprite = sprite(blockModel, facade, d, rand);
            // CCW as seen from outside, matching vanilla cube winding
            float[][] v = switch (d) {
                case UP -> new float[][] { { x0, y1, z0 }, { x0, y1, z1 }, { x1, y1, z1 }, { x1, y1, z0 } };
                case DOWN -> new float[][] { { x0, y0, z1 }, { x0, y0, z0 }, { x1, y0, z0 }, { x1, y0, z1 } };
                case NORTH -> new float[][] { { x1, y1, z0 }, { x1, y0, z0 }, { x0, y0, z0 }, { x0, y1, z0 } };
                case SOUTH -> new float[][] { { x0, y1, z1 }, { x0, y0, z1 }, { x1, y0, z1 }, { x1, y1, z1 } };
                case WEST -> new float[][] { { x0, y1, z0 }, { x0, y0, z0 }, { x0, y0, z1 }, { x0, y1, z1 } };
                case EAST -> new float[][] { { x1, y1, z1 }, { x1, y0, z1 }, { x1, y0, z0 }, { x1, y1, z0 } };
            };
            int[] data = new int[32];
            for (int i = 0; i < 4; i++) {
                float[] p = v[i];
                float u16;
                float v16;
                switch (d) {
                    case UP, DOWN -> { u16 = p[0] * 16f; v16 = p[2] * 16f; }
                    case NORTH -> { u16 = 16f - p[0] * 16f; v16 = 16f - p[1] * 16f; }
                    case SOUTH -> { u16 = p[0] * 16f; v16 = 16f - p[1] * 16f; }
                    case WEST -> { u16 = p[2] * 16f; v16 = 16f - p[1] * 16f; }
                    default -> { u16 = 16f - p[2] * 16f; v16 = 16f - p[1] * 16f; } // EAST
                }
                int o = i * 8;
                data[o] = Float.floatToRawIntBits(p[0]);
                data[o + 1] = Float.floatToRawIntBits(p[1]);
                data[o + 2] = Float.floatToRawIntBits(p[2]);
                data[o + 3] = 0xFFFFFFFF; // white; brightness applied by ModelBlockRenderer
                data[o + 4] = Float.floatToRawIntBits(sprite.getU(u16));
                data[o + 5] = Float.floatToRawIntBits(sprite.getV(v16));
                data[o + 6] = 0; // lightmap, filled by the renderer
                data[o + 7] = packNormal(d);
            }
            quads.put(d, List.of(new BakedQuad(data, -1, d, sprite, true)));
        }
        return new PlateModel(quads, blockModel.getParticleIcon(ModelData.EMPTY));
    }

    private static int packNormal(Direction d) {
        return (((byte) (d.getStepX() * 127)) & 0xFF)
                | ((((byte) (d.getStepY() * 127)) & 0xFF) << 8)
                | ((((byte) (d.getStepZ() * 127)) & 0xFF) << 16);
    }

    private static TextureAtlasSprite sprite(BakedModel model, BlockState state, Direction d, RandomSource rand) {
        List<BakedQuad> quads = model.getQuads(state, d, rand);
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
     * Minimal baked model wrapping the plate quads. Every quad is registered under its facing as the cull
     * face, so {@code ModelBlockRenderer} culls plate faces hidden by solid neighbours — like a real block.
     */
    private record PlateModel(Map<Direction, List<BakedQuad>> quads,
                              TextureAtlasSprite particle) implements BakedModel {

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction direction,
                                        RandomSource random) {
            return direction == null ? List.of() : quads.getOrDefault(direction, List.of());
        }

        @Override
        public boolean useAmbientOcclusion() {
            return true;
        }

        @Override
        public boolean isGui3d() {
            return true;
        }

        @Override
        public boolean usesBlockLight() {
            return true;
        }

        @Override
        public boolean isCustomRenderer() {
            return false;
        }

        @Override
        public TextureAtlasSprite getParticleIcon() {
            return particle;
        }

        @Override
        public ItemTransforms getTransforms() {
            return ItemTransforms.NO_TRANSFORMS;
        }

        @Override
        public ItemOverrides getOverrides() {
            return ItemOverrides.EMPTY;
        }
    }
}
