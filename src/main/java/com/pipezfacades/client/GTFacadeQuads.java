package com.pipezfacades.client;

import com.pipezfacades.client.gt.FaceQuad;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.client.model.data.ModelData;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Verbatim port of GregTech CEu Modern's facade cover quad generation into a single helper.
 *
 * <p>Mirrors, per non-null facade on a face:
 * <ul>
 *   <li>the grey plate cube emitted by {@code ICoverableRenderer.renderCovers} (GT
 *       {@code ICoverableRenderer.java:53-79}); and</li>
 *   <li>the camo facade quads emitted by {@code FacadeCoverRenderer.renderCover} (GT
 *       {@code FacadeCoverRenderer.java:113-137}), i.e. the front-face model quads for
 *       {@code side == attachedSide} and the {@code side == null} back-side rebake.</li>
 * </ul>
 *
 * <p>Facts baked in for the pipe host (per task): coverPlateThickness = 1/16 (so the
 * {@code thickness > 0} plate gate is always satisfied); {@code shouldRenderBackSide() == true};
 * modelFacing/modelState are identity and thus dropped; the camo model is queried with layer
 * {@code null}. The grey-plate sprite (GT: {@code gtceu:block/material_sets/dull/wire_side}) is
 * replaced by {@code pipezfacades:block/cover_plate}.
 */
public final class GTFacadeQuads {

    private GTFacadeQuads() {}

    /** Replaces GT's {@code GTCEu.id("block/material_sets/dull/wire_side")} plate sprite. */
    private static final ResourceLocation PLATE_SPRITE = new ResourceLocation("pipezfacades", "block/cover_plate");

    /** Pipe {@code getCoverPlateThickness()} == 1/16 (ICoverableRenderer.java:53). */
    private static final double THICKNESS = 1.0 / 16.0;

    /**
     * Emit, for every non-null facade in {@code facades} (indexed by {@link Direction#ordinal()}),
     * exactly the quads GT's {@code renderCovers} + {@code renderCover} produce for {@code querySide}.
     *
     * @param out       sink for baked quads (GT's {@code quads} list)
     * @param querySide  GT's {@code side} (null = the block-model "general"/back pass)
     * @param level      block/tint getter passed straight through to model data
     * @param pos        block position passed straight through to model data
     * @param rand       random source forwarded to {@code getQuads}
     * @param facades    length-6 array indexed by {@code Direction.ordinal()}; null = no facade
     */
    public static void appendCovers(List<BakedQuad> out, @Nullable Direction querySide,
                                    BlockAndTintGetter level, BlockPos pos, RandomSource rand,
                                    BlockState[] facades) {
        var mc = Minecraft.getInstance();
        // GT: ModelFactory.getBlockSprite(GTCEu.id("block/material_sets/dull/wire_side"))
        // (ICoverableRenderer.java:71,76) -> here the pipezfacades plate sprite.
        TextureAtlasSprite plateSprite = mc.getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(PLATE_SPRITE);

        // ICoverableRenderer.java:54 -> for (Direction face : GTUtil.DIRECTIONS) { ... }
        // GTUtil.DIRECTIONS == Direction.values() (GTUtil.java:85).
        for (Direction face : Direction.values()) {
            BlockState facade = facades[face.ordinal()];
            if (facade == null) {
                // ICoverableRenderer.java:56 -> if (cover != null)
                continue;
            }

            // ================= Grey plate cube =================
            // ICoverableRenderer.java:57 -> if (thickness > 0 && cover.shouldRenderPlate())
            // thickness == 1/16 > 0 (constant), shouldRenderPlate() == facadeState.canOcclude()
            // (FacadeCover.java:49-51).
            if (facade.canOcclude()) {
                double min = THICKNESS;                 // ICoverableRenderer.java:58
                double max = 1d - THICKNESS;            // ICoverableRenderer.java:59
                Vec3i normal = face.getNormal();        // ICoverableRenderer.java:60
                // ICoverableRenderer.java:61-67
                AABB cube = new AABB(
                        normal.getX() == 0 ? 0.001 : normal.getX() > 0 ? max : 0.001,
                        normal.getY() == 0 ? 0.001 : normal.getY() > 0 ? max : 0.001,
                        normal.getZ() == 0 ? 0.001 : normal.getZ() > 0 ? max : 0.001,
                        normal.getX() == 0 ? 0.999 : normal.getX() > 0 ? 0.999 : min,
                        normal.getY() == 0 ? 0.999 : normal.getY() > 0 ? 0.999 : min,
                        normal.getZ() == 0 ? 0.999 : normal.getZ() > 0 ? 0.999 : min);
                if (querySide == null) {
                    // ICoverableRenderer.java:68-72 -> render back: face.getOpposite()
                    out.add(FaceQuad.builder(face.getOpposite(), plateSprite)
                            .cube(cube).cubeUV().tintIndex(-1).bake());
                } else if (querySide != face.getOpposite()) {
                    // ICoverableRenderer.java:73-77 -> render sides: the query side itself
                    out.add(FaceQuad.builder(querySide, plateSprite)
                            .cube(cube).cubeUV().tintIndex(-1).bake());
                }
            }

            // ================= Camo facade quads =================
            // ICoverableRenderer.java:80 -> cover.getCoverRenderer().renderCover(...)
            // FacadeCoverRenderer.renderCover: state = facadeCover.getFacadeState() (== facade here;
            // FacadeCover.java:33 @Getter / FacadeCoverRenderer.java:114).
            // FacadeCoverRenderer.java:115 -> if (state.getRenderShape() == RenderShape.MODEL)
            if (facade.getRenderShape() == RenderShape.MODEL) {
                // FacadeCoverRenderer.java:116-117
                BakedModel model = mc.getBlockRenderer().getBlockModel(facade);
                if (querySide == face) {
                    // FacadeCoverRenderer.java:118-119 -> side == coverBehavior.attachedSide (== face).
                    // ModelUtil.getBakedModelQuads (ModelUtil.java:19-22):
                    //   model.getQuads(state, side, rand, model.getModelData(level,pos,state,EMPTY), null)
                    out.addAll(model.getQuads(facade, querySide, rand,
                            model.getModelData(level, pos, facade, ModelData.EMPTY), null));
                } else if (querySide == null) {
                    // FacadeCoverRenderer.java:120 -> side == null && shouldRenderBackSide() (== true).
                    Vec3i normal = face.getNormal();     // attachedSide.getNormal() (line 121)
                    // FacadeCoverRenderer.java:122-128 (0/1-based, no thickness)
                    AABB cube = new AABB(
                            normal.getX() == 0 ? 0 : normal.getX() > 0 ? 1 : 0,
                            normal.getY() == 0 ? 0 : normal.getY() > 0 ? 1 : 0,
                            normal.getZ() == 0 ? 0 : normal.getZ() > 0 ? 1 : 0,
                            normal.getX() == 0 ? 1 : normal.getX() > 0 ? 1 : 0,
                            normal.getY() == 0 ? 1 : normal.getY() > 0 ? 1 : 0,
                            normal.getZ() == 0 ? 1 : normal.getZ() > 0 ? 1 : 0);
                    // FacadeCoverRenderer.java:129-130 -> getBakedModelQuads(model, level, pos, state,
                    //   attachedSide, rand) == ModelUtil.java:19-22 with side = attachedSide (== face).
                    List<BakedQuad> backQuads = model.getQuads(facade, face, rand,
                            model.getModelData(level, pos, facade, ModelData.EMPTY), null);
                    // FacadeCoverRenderer.java:131-135 -> rebake each onto attachedSide.getOpposite()
                    for (BakedQuad quad : backQuads) {
                        out.add(FaceQuad.builder(face.getOpposite(), quad.getSprite())
                                .cube(cube)
                                .shade(quad.isShade())
                                .tintIndex(quad.getTintIndex())
                                .bake());
                    }
                }
            }
        }
    }
}
