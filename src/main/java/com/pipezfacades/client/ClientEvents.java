package com.pipezfacades.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.pipezfacades.ClientFacadeStore;
import com.pipezfacades.GridUtil;
import com.pipezfacades.PipeUtil;
import com.pipezfacades.PipezFacades;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderHighlightEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client-only glue:
 * <ul>
 *     <li>the GregTech-style cover grid overlay while aiming at a pipe with a facade item
 *         ({@link RenderHighlightEvent.Block}) — pulsing grid lines at 0.25/0.75 and GT's cell icons;</li>
 *     <li>chunk rebuild marking when facades change (facades are baked into the chunk mesh by
 *         {@link FacadedPipeModel});</li>
 *     <li>client store cleanup on chunk/level unload.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = PipezFacades.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientEvents {

    private ClientEvents() {
    }

    /** Marks the sections around a changed facade dirty so the chunk mesh rebuilds with the new quads. */
    public static void markFacadeDirty(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.levelRenderer != null) {
            mc.levelRenderer.setBlocksDirty(pos.getX() - 1, pos.getY() - 1, pos.getZ() - 1,
                    pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
        }
    }

    // ------------------------------------------------------------------ grid overlay

    @SubscribeEvent
    public static void onRenderHighlight(RenderHighlightEvent.Block event) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) {
            return;
        }
        BlockHitResult target = event.getTarget();
        BlockPos pos = target.getBlockPos();
        if (!PipeUtil.isPipe(level.getBlockState(pos))) {
            return;
        }

        ItemStack held = player.getMainHandItem();
        boolean placing = PipeUtil.resolveFacade(held) != null && PipeUtil.canPlaceNow(player, held);
        boolean removing = held.isEmpty() && player.isShiftKeyDown() && ClientFacadeStore.anyAt(pos);
        if (!placing && !removing) {
            return;
        }

        Vec3 cam = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        drawGridOverlay(poseStack, event.getMultiBufferSource(), target, pos, placing);
        poseStack.popPose();
    }

    /**
     * Face basis: origin corner + the two in-plane axes, the sides the u/v bands map to, and how icon
     * texture coordinates map onto (u, v) so the icon reads like a GUI facing the viewer (GT's
     * {@code rotateToFace} equivalent): {@code mirrorU} flips texture U, {@code flipV} makes texture V
     * run along +v instead of -v. Derived per face from "texture-down" = world down (or south on Y faces,
     * matching GT's {@code rotateToFace(facing, SOUTH)}).
     */
    private record FaceBasis(float ox, float oy, float oz,
                             float ux, float uy, float uz,
                             float vx, float vy, float vz,
                             Direction uNeg, Direction uPos, Direction vNeg, Direction vPos,
                             boolean mirrorU, boolean flipV) {

        float px(float u, float v) {
            return ox + ux * u + vx * v;
        }

        float py(float u, float v) {
            return oy + uy * u + vy * v;
        }

        float pz(float u, float v) {
            return oz + uz * u + vz * v;
        }
    }

    private static FaceBasis basis(BlockPos pos, Direction face) {
        final float eps = 0.01f;
        float minX = pos.getX(), minY = pos.getY(), minZ = pos.getZ();
        float maxX = minX + 1, maxY = minY + 1, maxZ = minZ + 1;
        // u/v mappings mirror GridUtil.gridSide exactly; mirrorU/flipV from r = texDown x viewDir per face.
        return switch (face) {
            case DOWN -> new FaceBasis(minX, minY - eps, minZ, 1, 0, 0, 0, 0, 1,
                    Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH, true, true);
            case UP -> new FaceBasis(minX, maxY + eps, minZ, 1, 0, 0, 0, 0, 1,
                    Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH, false, true);
            case NORTH -> new FaceBasis(minX, minY, minZ - eps, 1, 0, 0, 0, 1, 0,
                    Direction.WEST, Direction.EAST, Direction.DOWN, Direction.UP, true, false);
            case SOUTH -> new FaceBasis(minX, minY, maxZ + eps, 1, 0, 0, 0, 1, 0,
                    Direction.WEST, Direction.EAST, Direction.DOWN, Direction.UP, false, false);
            case WEST -> new FaceBasis(minX - eps, minY, minZ, 0, 0, 1, 0, 1, 0,
                    Direction.NORTH, Direction.SOUTH, Direction.DOWN, Direction.UP, false, false);
            default -> new FaceBasis(maxX + eps, minY, minZ, 0, 0, 1, 0, 1, 0, // EAST
                    Direction.NORTH, Direction.SOUTH, Direction.DOWN, Direction.UP, true, false);
        };
    }

    private static Direction cellSide(FaceBasis b, Direction face, int iu, int iv) {
        if (iu == 1 && iv == 1) {
            return face;
        }
        if (iu == 1) {
            return iv == 0 ? b.vNeg() : b.vPos();
        }
        if (iv == 1) {
            return iu == 0 ? b.uNeg() : b.uPos();
        }
        return face.getOpposite(); // corners, exactly like GT
    }

    /** GT's cover grid icons, copied from GTCEu ({@code textures/gui/overlay/tool_*_cover.png}). */
    private static final ResourceLocation ICON_ATTACH = new ResourceLocation(PipezFacades.MODID,
            "textures/gui/tool_attach_cover.png");
    private static final ResourceLocation ICON_REMOVE = new ResourceLocation(PipezFacades.MODID,
            "textures/gui/tool_remove_cover.png");

    private static void drawGridOverlay(PoseStack poseStack, MultiBufferSource buffers,
                                        BlockHitResult target, BlockPos pos, boolean placing) {
        Direction face = target.getDirection();
        Direction hovered = GridUtil.gridSide(target);
        FaceBasis b = basis(pos, face);
        var pose = poseStack.last();

        // --- pulsing grid lines at 0.25 / 0.75, GT-style ---
        float rg = 0.2f + (float) Math.sin((System.currentTimeMillis() % (Mth.PI * 800)) / 800) / 2;
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        RenderSystem.lineWidth(3);
        for (float t : new float[] { 0.25f, 0.75f }) {
            line(lines, pose, b, t, 0f, t, 1f, rg);
            line(lines, pose, b, 0f, t, 1f, t, rg);
        }

        // --- cell icons, GT's exact draw path (GUI shader, blend on, depth off, no lightmap) ---
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, placing ? ICON_ATTACH : ICON_REMOVE);
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        final float[] bands = { 0f, 0.25f, 0.75f, 1f };
        final float iconSize = 0.25f * 0.9f; // 4/16 px cell icon, scaled 0.9 like GT
        for (int iu = 0; iu < 3; iu++) {
            for (int iv = 0; iv < 3; iv++) {
                Direction side = cellSide(b, face, iu, iv);
                boolean has = ClientFacadeStore.get(pos, side) != null;
                if (placing == has) {
                    continue; // GT: no icon on occupied cells while placing; ours: only occupied while removing
                }
                float cu = (bands[iu] + bands[iu + 1]) / 2f;
                float cv = (bands[iv] + bands[iv + 1]) / 2f;
                int alpha = side == hovered ? 0xFF : 0x44; // GT: -1 hovered, 0x44ffffff idle
                iconQuad(buffer, pose, b,
                        cu - iconSize / 2f, cv - iconSize / 2f,
                        cu + iconSize / 2f, cv + iconSize / 2f, alpha);
            }
        }
        tesselator.end();
        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
    }

    private static void line(VertexConsumer vc, PoseStack.Pose pose, FaceBasis b,
                             float u0, float v0, float u1, float v1, float rg) {
        float x0 = b.px(u0, v0), y0 = b.py(u0, v0), z0 = b.pz(u0, v0);
        float x1 = b.px(u1, v1), y1 = b.py(u1, v1), z1 = b.pz(u1, v1);
        float nx = x1 - x0, ny = y1 - y0, nz = z1 - z0;
        float len = Mth.sqrt(nx * nx + ny * ny + nz * nz);
        nx /= len;
        ny /= len;
        nz /= len;
        vc.vertex(pose.pose(), x0, y0, z0).color(rg, rg, 1f, 1f).normal(pose.normal(), nx, ny, nz).endVertex();
        vc.vertex(pose.pose(), x1, y1, z1).color(rg, rg, 1f, 1f).normal(pose.normal(), nx, ny, nz).endVertex();
    }

    /**
     * One textured icon quad on the face plane (both windings, so it reads from any angle), in GT's GUI
     * vertex format (position + uv + colour, no lightmap). Texture orientation comes from the basis'
     * {@code mirrorU}/{@code flipV}, so the icon is upright and unmirrored for a viewer outside the face.
     */
    private static void iconQuad(BufferBuilder buffer, PoseStack.Pose pose, FaceBasis b,
                                 float u0, float v0, float u1, float v1, int alpha) {
        // front
        iconVertex(buffer, pose, b, u0, v0, 0f, 0f, alpha);
        iconVertex(buffer, pose, b, u1, v0, 1f, 0f, alpha);
        iconVertex(buffer, pose, b, u1, v1, 1f, 1f, alpha);
        iconVertex(buffer, pose, b, u0, v1, 0f, 1f, alpha);
        // back
        iconVertex(buffer, pose, b, u0, v0, 0f, 0f, alpha);
        iconVertex(buffer, pose, b, u0, v1, 0f, 1f, alpha);
        iconVertex(buffer, pose, b, u1, v1, 1f, 1f, alpha);
        iconVertex(buffer, pose, b, u1, v0, 1f, 0f, alpha);
    }

    private static void iconVertex(BufferBuilder buffer, PoseStack.Pose pose, FaceBasis b,
                                   float u, float v, float relU, float relV, int alpha) {
        float texU = b.mirrorU() ? 1f - relU : relU;
        float texV = b.flipV() ? relV : 1f - relV;
        buffer.vertex(pose.pose(), b.px(u, v), b.py(u, v), b.pz(u, v))
                .uv(texU, texV)
                .color(255, 255, 255, alpha)
                .endVertex();
    }

    // ------------------------------------------------------------------ cleanup

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            ClientFacadeStore.clearChunk(event.getChunk().getPos());
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            ClientFacadeStore.clear();
        }
    }
}
