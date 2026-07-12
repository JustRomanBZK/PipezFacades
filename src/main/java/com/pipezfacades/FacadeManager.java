package com.pipezfacades;

import com.pipezfacades.net.Network;
import com.pipezfacades.net.S2CFacadePacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Server-side facade operations: install, remove, clear — with drops, sounds and client sync. */
public final class FacadeManager {

    private FacadeManager() {
    }

    public static boolean has(ServerLevel level, BlockPos pos, Direction side) {
        return FacadeSavedData.get(level).has(pos, side);
    }

    /**
     * Installs {@code facade} on {@code side} of the pipe at {@code pos}. {@code dropTemplate} is the
     * exact item returned when the facade is later removed (the GT facade item itself, or the block).
     * Replaces (and pops) any facade already on that side.
     */
    public static void apply(ServerLevel level, BlockPos pos, Direction side, BlockState facade,
                             ItemStack dropTemplate, Player player, ItemStack held) {
        FacadeSavedData data = FacadeSavedData.get(level);
        FacadeSavedData.Entry old = data.get(pos, side);
        if (old != null && !player.getAbilities().instabuild) {
            Block.popResource(level, pos, old.drop().copy());
        }
        data.set(pos, side, new FacadeSavedData.Entry(facade, dropTemplate));
        Network.sendToTracking(level, pos, new S2CFacadePacket(List.of(
                new S2CFacadePacket.Entry(pos, side.ordinal(), Block.getId(facade)))));

        SoundType sound = facade.getSoundType(level, pos, player);
        level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS,
                (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);

        if (!player.getAbilities().instabuild) {
            held.shrink(1);
        }
    }

    /** Pops the facade off one side. Returns true if there was one. */
    public static boolean remove(ServerLevel level, BlockPos pos, Direction side, @Nullable Player player) {
        FacadeSavedData.Entry entry = FacadeSavedData.get(level).remove(pos, side);
        if (entry == null) {
            return false;
        }
        if (player == null || !player.getAbilities().instabuild) {
            Block.popResource(level, pos, entry.drop().copy());
        }
        Network.sendToTracking(level, pos, new S2CFacadePacket(List.of(
                new S2CFacadePacket.Entry(pos, side.ordinal(), S2CFacadePacket.REMOVE))));

        SoundType sound = entry.state().getSoundType(level, pos, player);
        level.playSound(null, pos, sound.getBreakSound(), SoundSource.BLOCKS,
                (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
        return true;
    }

    /** Clears every facade at the position (pipe broken). Drops unless the breaking player is creative. */
    public static void clearAll(ServerLevel level, BlockPos pos, @Nullable Player player) {
        FacadeSavedData.Entry[] removed = FacadeSavedData.get(level).removeAll(pos);
        if (removed == null) {
            return;
        }
        boolean drop = player == null || !player.getAbilities().instabuild;
        for (FacadeSavedData.Entry entry : removed) {
            if (entry != null && drop) {
                Block.popResource(level, pos, entry.drop().copy());
            }
        }
        Network.sendToTracking(level, pos, new S2CFacadePacket(List.of(
                new S2CFacadePacket.Entry(pos, S2CFacadePacket.SIDE_CLEAR_ALL, S2CFacadePacket.REMOVE))));
    }
}
