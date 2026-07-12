package com.pipezfacades.client;

import com.pipezfacades.ClientFacadeStore;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
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
 * Wraps every pipez pipe blockstate model and bakes facade quads INTO the chunk mesh, driving the ported
 * quad generation exactly like GregTech CEu Modern's {@code PipeBlockRenderer.renderModel}
 * (GT {@code PipeBlockRenderer.java:70-92}).
 *
 * <h2>How this mirrors GT's stack</h2>
 * GT reaches the same result through two layers:
 * <ul>
 *   <li>{@code LDLRendererModel.RendererBakedModel} (LDLib) packs {@code WORLD}(=level), {@code POS}(=pos)
 *       and {@code IRENDERER} into the {@link ModelData} in {@code getModelData}, then in {@code getQuads}
 *       reads them back and calls {@code renderer.renderModel(world, pos, state, side, rand)}
 *       (LDLib {@code LDLRendererModel.java:100-148}).</li>
 *   <li>{@code PipeBlockRenderer.renderModel} then emits pipe-body quads followed by the cover quads from
 *       {@code ICoverableRenderer.renderCovers} + {@code FacadeCoverRenderer.renderCover}.</li>
 * </ul>
 * pipez has no {@code IRenderer}/{@code ICoverable} system and no {@code PipeBlockEntity}, so this class
 * fuses both layers: it packs {@link #WORLD}/{@link #POS} exactly like {@code RendererBakedModel}, and its
 * {@link #getQuads} plays the role of {@code renderModel} — pipe quads first, then all cover quads, the
 * latter produced verbatim by {@link GTFacadeQuads#appendCovers} (the line-for-line port of
 * {@code renderCovers}+{@code renderCover}). Per-position facade state stands in for GT's per-side
 * {@code CoverBehavior}s and arrives, without any block entity, through {@link #getModelData} during chunk
 * building; the client store is filled by the sync packet, which also dirties the section for a rebuild.
 */
public class FacadedPipeModel extends BakedModelWrapper<BakedModel> {

    /**
     * Per-side facade states at the pipe's position (index = {@link Direction#ordinal()}), or absent.
     * Stands in for GT's {@code coverable.getCoverAtSide(face)} lookups.
     */
    public static final ModelProperty<BlockState[]> FACADES = new ModelProperty<>();

    /**
     * The {@link BlockAndTintGetter} for this position, packed exactly like
     * {@code RendererBakedModel.WORLD} (LDLib {@code LDLRendererModel.java:93}) so {@link #getQuads} can
     * forward it to the camo model's {@code getModelData} the way GT's {@code renderModel} forwards its
     * {@code level} into {@code renderCovers} -> {@code FacadeCoverRenderer} -> {@code ModelUtil}.
     * Treated ONLY as a {@code BlockAndTintGetter}: Embeddium supplies a {@code WorldSlice} off-thread, so
     * this is never cast to {@code Level}.
     */
    public static final ModelProperty<BlockAndTintGetter> WORLD = new ModelProperty<>();

    /** The block position, packed exactly like {@code RendererBakedModel.POS} (LDLib {@code :94}). */
    public static final ModelProperty<BlockPos> POS = new ModelProperty<>();

    public FacadedPipeModel(BakedModel original) {
        super(original);
    }

    /**
     * Mirrors {@code RendererBakedModel.getModelData} (LDLib {@code LDLRendererModel.java:135-148}), which
     * builds a fresh {@link ModelData} carrying {@code WORLD}/{@code POS} (and the wrapped model's own
     * data). Here the per-position facade snapshot replaces GT's {@code IRENDERER}. All three properties
     * are stored together, so whenever {@link #FACADES} is present {@link #WORLD}/{@link #POS} are too.
     *
     * <p>Thread-safe: {@link ClientFacadeStore} is a concurrent map; the shared array is cloned into an
     * immutable snapshot, {@code pos} is made immutable, and the resulting {@link ModelData} is immutable.
     * {@code level} is stored purely as a {@link BlockAndTintGetter} (no {@code Level} cast).
     */
    @Override
    public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state,
                                  ModelData modelData) {
        ModelData base = super.getModelData(level, pos, state, modelData);
        BlockState[] sides = ClientFacadeStore.getSides(pos);
        if (sides == null) {
            return base;
        }
        return base.derive()
                .with(FACADES, sides.clone())
                .with(WORLD, level)
                .with(POS, pos.immutable())
                .build();
    }

    /**
     * When a facade is present, add {@link RenderType#cutoutMipped()} to the pipe's own layers. This is the
     * single layer GT bakes ALL cover quads into: GTCEu registers the pipe block with
     * {@code ItemBlockRenderTypes.setRenderLayer(cutoutMipped)} and the camo model is queried with layer
     * {@code null}, so every plate/camo/backside quad flows into cutoutMipped regardless of the camo
     * block's own layer.
     */
    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data) {
        ChunkRenderTypeSet base = super.getRenderTypes(state, rand, data);
        if (data.get(FACADES) == null) {
            return base;
        }
        return ChunkRenderTypeSet.union(base, ChunkRenderTypeSet.of(RenderType.cutoutMipped()));
    }

    /**
     * Plays the role of {@code PipeBlockRenderer.renderModel} (GT {@code PipeBlockRenderer.java:70-92}):
     * pipe-body quads first (GT {@code :74}), then every cover quad (GT {@code :77} ->
     * {@code renderCovers} -> {@code renderCover}), the latter delegated verbatim to
     * {@link GTFacadeQuads#appendCovers}.
     */
    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand,
                                    ModelData data, @Nullable RenderType renderType) {
        BlockState[] facades = data.get(FACADES);

        // GT's pipe body: pipeModel.bakeQuads(...) (PipeBlockRenderer.java:74). pipez cannot rebake the
        // pipe model per-connection, so it reuses the wrapped JSON model's quads — but ONLY for the pipe's
        // own layer(s). A plain JSON model returns its quads for whatever layer it is asked, so gate on the
        // wrapped model's real layer set to avoid duplicating pipe quads onto the facade-only cutoutMipped
        // layer (renderType == null means "all layers", e.g. item/particle/other-mod paths -> keep them).
        List<BakedQuad> pipeQuads = (renderType == null
                || super.getRenderTypes(state, rand, data).contains(renderType))
                        ? super.getQuads(state, side, rand, data, renderType)
                        : List.of();

        if (facades == null) {
            return pipeQuads;
        }

        List<BakedQuad> quads = new ArrayList<>();
        // GT hides the pipe's connection under a facade: FacadeCover.canPipePassThrough() == false, so the
        // pipe's visualConnections omit that side and bakeQuads emits no connection geometry there. pipez
        // keeps its connection arm (end cap sits exactly in the camo face plane and would z-fight), so it
        // drops the pipe's cull-face quads on facaded sides. side == null (unculled body) is always kept,
        // matching GT's unculled pipe body.
        if (side == null || facades[side.ordinal()] == null) {
            quads.addAll(pipeQuads);
        }

        // GT appends the cover quads right after the pipe body (PipeBlockRenderer.java:77). They all live on
        // the pipe's cutoutMipped layer; renderType == null is the "all layers" query (item/particle/etc.).
        if (renderType == null || renderType == RenderType.cutoutMipped()) {
            // The WORLD/POS packed in getModelData, mirroring RendererBakedModel.getQuads reading back
            // WORLD/POS (LDLRendererModel.java:103-104) before calling renderModel. getQuads has no
            // level/pos parameters (unlike renderModel), so the stored model data is the only source; it is
            // always populated alongside FACADES. mc.level (a BlockAndTintGetter, never cast to Level) is a
            // defensive fallback only.
            BlockAndTintGetter level = data.get(WORLD);
            BlockPos pos = data.get(POS);
            if (level == null) {
                level = Minecraft.getInstance().level;
            }
            if (pos == null) {
                pos = BlockPos.ZERO;
            }
            if (level != null) {
                // == ICoverableRenderer.renderCovers(quads, side, rand, coverable, modelFacing, pos, level,
                //    modelState) (PipeBlockRenderer.java:77). appendCovers is the line-for-line port of
                //    renderCovers + FacadeCoverRenderer.renderCover; modelFacing/modelState are identity for
                //    the pipe host and thus dropped.
                GTFacadeQuads.appendCovers(quads, side, level, pos, rand, facades);
            }
        }
        return quads;
    }
}
