package com.pipezfacades.client;

import com.pipezfacades.ClientFacadeStore;
import com.pipezfacades.PipezFacades;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.ChunkRenderTypeSet;
import net.minecraftforge.client.model.BakedModelWrapper;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Wraps every pipez pipe blockstate model and bakes facade quads INTO the model — so facades become
 * regular chunk geometry, exactly like GregTech's facade covers (GT injects its cover quads into the
 * pipe's block model via {@code ICoverableRenderer}/{@code FacadeCoverRenderer}, which LDLib bakes into
 * the chunk mesh). Chunk baking gives facades the same lighting, ambient occlusion, depth precision and
 * shader treatment as every other block — eliminating the tone and z-fighting differences a separate
 * per-frame render pass inevitably has.
 *
 * <p>The per-position facade data reaches the model through {@link #getModelData}, which Forge calls with
 * the position during chunk building (no block entity required); the client store is filled by the sync
 * packet, which also marks the chunk section dirty for a rebuild.
 *
 * <p>Quad generation is a verbatim port of GT's {@code ICoverableRenderer.renderCovers} (grey plate body:
 * 0.001–0.999 lateral, 1/16 thick, one cube face per cull-side query, inner face unculled) and
 * {@code FacadeCoverRenderer.renderCover} (camouflage face = the block model's own quads for that face;
 * back side = each front quad re-baked flat onto the boundary plane facing inward). UV projection matches
 * LDLib's {@code FaceQuad.cubeUV()}.
 */
public class FacadedPipeModel extends BakedModelWrapper<BakedModel> {

    /** Per-side facade states at the pipe's position (index = direction ordinal), or absent. */
    public static final ModelProperty<BlockState[]> FACADES = new ModelProperty<>();

    /** Plate thickness — GT pipes use min(1/16, (1 - pipeThickness) / 2) = 1/16 for regular pipes. */
    private static final float T = 1f / 16f;
    /** GT's lateral / inner inset for the plate body. */
    private static final float E = 0.001f;

    private static final ResourceLocation PLATE_SPRITE = new ResourceLocation(PipezFacades.MODID,
            "block/cover_plate");

    public FacadedPipeModel(BakedModel original) {
        super(original);
    }

    @Override
    public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state,
                                  ModelData modelData) {
        ModelData base = super.getModelData(level, pos, state, modelData);
        BlockState[] sides = ClientFacadeStore.getSides(pos);
        if (sides == null) {
            return base;
        }
        return base.derive().with(FACADES, sides.clone()).build();
    }

    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data) {
        ChunkRenderTypeSet base = super.getRenderTypes(state, rand, data);
        if (data.get(FACADES) == null) {
            return base;
        }
        // GT bakes ALL facade quads (camouflage, plate, backside) into the PIPE's single chunk layer —
        // RenderType.cutoutMipped() — never the camouflage block's own layer (verified:
        // ICoverableRenderer/FacadeCoverRenderer quads flow into the model that GTCEu registers with
        // ItemBlockRenderTypes.setRenderLayer(cutoutMipped); the camo model is queried with layer null).
        return ChunkRenderTypeSet.union(base, ChunkRenderTypeSet.of(RenderType.cutoutMipped()));
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand,
                                    ModelData data, @Nullable RenderType renderType) {
        BlockState[] sides = data.get(FACADES);
        // The pipe's own quads belong only to the pipe's own layers (plain JSON models return their quads
        // for whatever layer they're asked, so gate explicitly to avoid duplicates on the facade layer).
        List<BakedQuad> pipeQuads = (renderType == null ||
                super.getRenderTypes(state, rand, data).contains(renderType))
                        ? super.getQuads(state, side, rand, data, renderType) : List.of();
        if (sides == null) {
            return pipeQuads;
        }

        List<BakedQuad> quads = new ArrayList<>();
        // GT hides the pipe's connection visuals behind facades (FacadeCover.canPipePassThrough = false →
        // no visual connection on that side). pipez keeps its connection arm, whose end cap lies EXACTLY
        // in the camouflage face plane (pipe_part.json: from z=0) and z-fights it inside the mesh — so the
        // pipe's cull-face quads are dropped on facaded sides. Unculled pipe quads (arm/core side faces)
        // stay, like GT's pipe body.
        if (side == null || sides[side.ordinal()] == null) {
            quads.addAll(pipeQuads);
        }

        // All facade quads live on GT's facade layer only.
        if (renderType != null && renderType != RenderType.cutoutMipped()) {
            return quads;
        }
        Minecraft mc = Minecraft.getInstance();
        TextureAtlasSprite grey = mc.getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(PLATE_SPRITE);
        for (Direction face : Direction.values()) {
            BlockState fs = sides[face.ordinal()];
            if (fs == null) {
                continue;
            }
            BakedModel model = mc.getBlockRenderer().getBlockModel(fs);
            boolean plate = fs.canOcclude(); // GT: FacadeCover.shouldRenderPlate()
            float[] body = bodyBox(face);
            if (side == face) {
                // camouflage face: the camo model's own quads, queried with layer null like GT's
                // ModelUtil.getBakedModelQuads; plus the plate's outer face 0.001 behind it (GT emits it).
                quads.addAll(model.getQuads(fs, face, rand, ModelData.EMPTY, null));
                if (plate) {
                    quads.add(quad(face, body, grey, -1, true));
                }
            } else if (side == null) {
                // back side for EVERY facade (GT pipes: shouldRenderBackSide = true): each front quad
                // re-baked flat onto the boundary plane facing inward, keeping sprite/shade/tint...
                float[] plane = boundaryBox(face);
                for (BakedQuad front : model.getQuads(fs, face, rand, ModelData.EMPTY, null)) {
                    quads.add(quad(face.getOpposite(), plane, front.getSprite(), front.getTintIndex(),
                            front.isShade()));
                }
                // ...plus the plate's inner face at 1/16.
                if (plate) {
                    quads.add(quad(face.getOpposite(), body, grey, -1, true));
                }
            } else if (side != face.getOpposite()) {
                // plate rim toward the queried side — GT emits these unconditionally (no neighbor checks).
                if (plate) {
                    quads.add(quad(side, body, grey, -1, true));
                }
            }
        }
        return quads;
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
            data[o + 3] = 0xFFFFFFFF; // white; brightness/tint applied by the chunk renderer
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
}
