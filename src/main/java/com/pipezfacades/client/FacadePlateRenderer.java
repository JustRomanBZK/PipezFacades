package com.pipezfacades.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.pipezfacades.PipezFacades;
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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Draws a facade exactly the way GregTech draws facade covers:
 * <ul>
 *     <li>the camouflage block's face as a <b>full 16×16 quad at the block boundary</b> — it always wins
 *         visually over neighbouring plates' rims, which is GT's joint "priority";</li>
 *     <li>a thin plate body (rims + inner face) textured with GT's uniform grey cover-plate sprite, laterally
 *         inset to 0.001–0.999. Adjacent plates' grey parts may overlap, but because every plate uses the
 *         same sprite with world-projected UVs, overlapping pixels are identical and no z-fighting is
 *         visible — the same trick GT relies on.</li>
 * </ul>
 *
 * <p>The quads are assembled into an ad-hoc {@link BakedModel} and rendered through vanilla's
 * {@code ModelBlockRenderer} — the chunk-geometry code path — so the facade receives real ambient
 * occlusion, smooth lighting, per-face shading and neighbour-based face culling.
 */
public final class FacadePlateRenderer {

    /** Plate thickness, matching the collision plates and GT's cover plate. */
    private static final float T = 1f / 16f;
    /** Lateral / inner inset used by GT for the plate body (0.001–0.999). */
    private static final float E = 0.001f;

    private static final ResourceLocation PLATE_SPRITE = new ResourceLocation(PipezFacades.MODID,
            "block/cover_plate");

    private FacadePlateRenderer() {
    }

    /**
     * Renders one facade. The pose stack must already be translated to the pipe's position.
     *
     * @param allSides all facade states on this pipe (index = direction ordinal) — used to pull the grey
     *                 plate body back where a neighbouring facade's face plane would otherwise sit only
     *                 0.001 away and z-fight
     */
    public static void render(PoseStack poseStack, MultiBufferSource buffers, Level level, BlockPos pos,
                              Direction side, BlockState facade, BlockState[] allSides, RandomSource rand) {
        Minecraft mc = Minecraft.getInstance();
        BakedModel blockModel = mc.getBlockRenderer().getBlockModel(facade);
        PlateModel plate = buildPlate(mc, blockModel, facade, side, allSides, rand);

        RenderType renderType = renderTypeFor(blockModel, facade, rand);
        VertexConsumer vc = buffers.getBuffer(renderType);
        mc.getBlockRenderer().getModelRenderer().tesselateBlock(
                level, plate, facade, pos, poseStack, vc, true, rand, facade.getSeed(pos),
                OverlayTexture.NO_OVERLAY, ModelData.EMPTY, renderType);
    }

    /**
     * The camouflage block's chunk render type, taken from its MODEL (the modern Forge mechanism, which
     * modded blocks like GregTech glass use) rather than the legacy vanilla-only
     * {@code ItemBlockRenderTypes} map — that fallback returned {@code solid} for modded glass, whose
     * shader never discards transparent pixels, so the facade wrote depth everywhere and turned into an
     * x-ray window.
     */
    public static RenderType renderTypeFor(BakedModel model, BlockState facade, RandomSource rand) {
        var types = model.getRenderTypes(facade, rand, ModelData.EMPTY);
        if (types.contains(RenderType.translucent())) {
            return RenderType.translucent();
        }
        var it = types.iterator();
        if (it.hasNext()) {
            return it.next();
        }
        return ItemBlockRenderTypes.getChunkRenderType(facade);
    }

    /** True if this facade must be drawn in the translucent pass (after entities and water). */
    public static boolean isTranslucent(BlockState facade, RandomSource rand) {
        BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(facade);
        return renderTypeFor(model, facade, rand) == RenderType.translucent();
    }

    private static PlateModel buildPlate(Minecraft mc, BakedModel blockModel, BlockState facade,
                                         Direction side, BlockState[] allSides, RandomSource rand) {
        Map<Direction, List<BakedQuad>> culled = new EnumMap<>(Direction.class);
        List<BakedQuad> unculled = new ArrayList<>();

        // Camouflage face: the block model's OWN quads for that face, exactly like GT's
        // ModelUtil.getBakedModelQuads — original UVs, rotations, sprites and tint indices (biome
        // colours on grass/leaves work; texture pattern lines up pixel-perfect with real blocks).
        List<BakedQuad> face = blockModel.getQuads(facade, side, rand, ModelData.EMPTY, null);
        culled.put(side, List.copyOf(face));

        if (facade.canOcclude()) {
            // GT parity (FacadeCover.shouldRenderPlate): occluding facades get the grey plate body with
            // GT's exact extents (0.001–0.999 laterally). Rims facing a side that also has a facade are
            // skipped — they'd be fully hidden behind that facade's face anyway, and sitting 0.001 from
            // its plane they z-fight in this dynamic render pass (chunk geometry gets away with it).
            TextureAtlasSprite grey = mc.getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(PLATE_SPRITE);
            float[] body = bodyBox(side);
            for (Direction d : Direction.values()) {
                if (d.getAxis() != side.getAxis() && allSides[d.ordinal()] == null) {
                    culled.put(d, List.of(quad(d, body, grey, -1, true)));
                }
            }
            unculled.add(quad(side.getOpposite(), body, grey, -1, true));
        } else {
            // Non-occluding facades (glass, ...) render no plate in GT. Instead GT re-bakes each front
            // quad flat onto the boundary plane facing inward (FacadeCoverRenderer's back-side pass),
            // keeping the sprite, shade flag and tint index — replicated here verbatim.
            float[] plane = boundaryBox(side);
            for (BakedQuad front : face) {
                unculled.add(quad(side.getOpposite(), plane, front.getSprite(), front.getTintIndex(),
                        front.isShade()));
            }
        }

        return new PlateModel(culled, List.copyOf(unculled), blockModel.getParticleIcon(ModelData.EMPTY));
    }

    /** Degenerate box: the full face plane exactly at the block boundary of {@code side} (like GT). */
    private static float[] boundaryBox(Direction side) {
        float[] b = { 0, 0, 0, 1, 1, 1 };
        switch (side) {
            case NORTH -> b[5] = 0;
            case SOUTH -> b[2] = 1;
            case WEST -> b[3] = 0;
            case EAST -> b[0] = 1;
            case DOWN -> b[4] = 0;
            case UP -> b[1] = 1;
        }
        return b;
    }

    /** The grey plate body with GT's exact extents: thickness {@code T}, laterally 0.001–0.999. */
    private static float[] bodyBox(Direction side) {
        float x0 = E, y0 = E, z0 = E, x1 = 1 - E, y1 = 1 - E, z1 = 1 - E;
        switch (side) {
            case NORTH -> z1 = T;
            case SOUTH -> z0 = 1 - T;
            case WEST -> x1 = T;
            case EAST -> x0 = 1 - T;
            case DOWN -> y1 = T;
            case UP -> y0 = 1 - T;
        }
        return new float[] { x0, y0, z0, x1, y1, z1 };
    }

    /**
     * One axis-aligned quad of a box, CCW from outside, with position-projected UVs matching LDLib's
     * {@code FaceQuad.cubeUV()} exactly (note DOWN mirrors V: {@code v = 16 - z}).
     */
    private static BakedQuad quad(Direction d, float[] b, TextureAtlasSprite sprite, int tintIndex,
                                  boolean shade) {
        float x0 = b[0], y0 = b[1], z0 = b[2], x1 = b[3], y1 = b[4], z1 = b[5];
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
                case UP -> { u16 = p[0] * 16f; v16 = p[2] * 16f; }
                case DOWN -> { u16 = p[0] * 16f; v16 = 16f - p[2] * 16f; }
                case NORTH -> { u16 = 16f - p[0] * 16f; v16 = 16f - p[1] * 16f; }
                case SOUTH -> { u16 = p[0] * 16f; v16 = 16f - p[1] * 16f; }
                case WEST -> { u16 = p[2] * 16f; v16 = 16f - p[1] * 16f; }
                default -> { u16 = 16f - p[2] * 16f; v16 = 16f - p[1] * 16f; } // EAST
            }
            int o = i * 8;
            data[o] = Float.floatToRawIntBits(p[0]);
            data[o + 1] = Float.floatToRawIntBits(p[1]);
            data[o + 2] = Float.floatToRawIntBits(p[2]);
            data[o + 3] = 0xFFFFFFFF; // white; brightness/tint applied by ModelBlockRenderer
            data[o + 4] = Float.floatToRawIntBits(sprite.getU(u16));
            data[o + 5] = Float.floatToRawIntBits(sprite.getV(v16));
            data[o + 6] = 0; // lightmap, filled by the renderer
            data[o + 7] = packNormal(d);
        }
        return new BakedQuad(data, tintIndex, d, sprite, shade);
    }

    private static int packNormal(Direction d) {
        return (((byte) (d.getStepX() * 127)) & 0xFF)
                | ((((byte) (d.getStepY() * 127)) & 0xFF) << 8)
                | ((((byte) (d.getStepZ() * 127)) & 0xFF) << 16);
    }

    /**
     * Minimal baked model wrapping the plate quads. Culled quads sit under their facing as the cull face,
     * so {@code ModelBlockRenderer} hides faces covered by solid neighbours — like a real block; the inner
     * face is registered unculled.
     */
    private record PlateModel(Map<Direction, List<BakedQuad>> culled, List<BakedQuad> unculled,
                              TextureAtlasSprite particle) implements BakedModel {

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction direction,
                                        RandomSource random) {
            return direction == null ? unculled : culled.getOrDefault(direction, List.of());
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
