package com.pipezfacades;

import com.pipezfacades.net.Network;
import com.pipezfacades.net.S2CFacadePacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ChunkWatchEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * Game-bus handlers that drive facade interaction — GregTech-style side selection via the hit grid
 * (see {@link GridUtil}), no mixins into pipez.
 *
 * <p><b>Controls</b>
 * <ul>
 *     <li><b>GregTech facade item</b>: right-click a pipe — the facade goes onto the grid side under the
 *         crosshair (centre = the face you're looking at, edges = perpendicular sides, corners = the far
 *         side). Sneaking not required, exactly like placing GT covers.</li>
 *     <li><b>Any plain full block</b>: sneak + right-click — same grid selection. (Sneak is required so
 *         normal building against pipes keeps working.)</li>
 *     <li><b>Sneak + empty hand</b>: pops the facade off the grid side (falls back to the hit face).</li>
 *     <li><b>Breaking the pipe</b> drops every installed facade.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = PipezFacades.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CommonEvents {

    private CommonEvents() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        if (!PipeUtil.isPipe(state)) {
            return;
        }

        Player player = event.getEntity();
        ItemStack held = event.getItemStack();
        BlockHitResult hit = event.getHitVec();
        boolean server = !level.isClientSide;

        // --- removal: sneak + empty hand -------------------------------------------------------
        if (held.isEmpty()) {
            if (!player.isShiftKeyDown()) {
                return;
            }
            Direction gridSide = GridUtil.gridSide(hit);
            Direction target = null;
            if (hasFacade(level, pos, gridSide)) {
                target = gridSide;
            } else if (hasFacade(level, pos, hit.getDirection())) {
                target = hit.getDirection();
            }
            if (target == null) {
                return; // nothing to remove — don't swallow the interaction
            }
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            if (server) {
                FacadeManager.remove((ServerLevel) level, pos, target, player);
            }
            return;
        }

        // --- placement --------------------------------------------------------------------------
        BlockState facade = PipeUtil.resolveFacade(held);
        if (facade == null || !PipeUtil.canPlaceNow(player, held)) {
            return;
        }
        Direction side = GridUtil.gridSide(hit);
        // GT parity: clicking a side that already has a facade does nothing (no replace, no sound).
        if (hasFacade(level, pos, side)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (server) {
            FacadeManager.apply((ServerLevel) level, pos, side, facade, held.copyWithCount(1), player, held);
        }
    }

    private static boolean hasFacade(Level level, BlockPos pos, Direction side) {
        if (level instanceof ServerLevel serverLevel) {
            return FacadeManager.has(serverLevel, pos, side);
        }
        return ClientFacadeStore.get(pos, side) != null;
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!PipeUtil.isPipe(event.getState())) {
            return;
        }
        FacadeManager.clearAll(level, event.getPos(), event.getPlayer());
    }

    /** When a player starts tracking a chunk, send them every facade in it so they render immediately. */
    @SubscribeEvent
    public static void onChunkWatch(ChunkWatchEvent.Watch event) {
        ServerLevel level = event.getLevel();
        FacadeSavedData data = FacadeSavedData.get(level);
        List<S2CFacadePacket.Entry> entries = new ArrayList<>();
        List<BlockPos> stale = new ArrayList<>();
        data.forEachInChunk(event.getPos(), (pos, sides) -> {
            // lazy cleanup: the pipe may have been destroyed by an explosion or another mod
            if (!PipeUtil.isPipe(level.getBlockState(pos))) {
                stale.add(pos);
                return;
            }
            for (int i = 0; i < 6; i++) {
                if (sides[i] != null) {
                    entries.add(new S2CFacadePacket.Entry(pos, i, Block.getId(sides[i].state())));
                }
            }
        });
        stale.forEach(data::removeAll);
        if (!entries.isEmpty()) {
            Network.sendTo(event.getPlayer(), new S2CFacadePacket(entries));
        }
    }
}
