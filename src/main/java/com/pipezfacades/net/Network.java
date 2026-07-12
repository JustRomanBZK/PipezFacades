package com.pipezfacades.net;

import com.pipezfacades.PipezFacades;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class Network {

    private static final String PROTOCOL = "1";

    public static SimpleChannel CHANNEL;

    private Network() {
    }

    public static void register() {
        CHANNEL = NetworkRegistry.newSimpleChannel(
                new ResourceLocation(PipezFacades.MODID, "main"),
                () -> PROTOCOL,
                PROTOCOL::equals,
                PROTOCOL::equals);

        int id = 0;
        CHANNEL.registerMessage(id++, S2CFacadePacket.class,
                S2CFacadePacket::encode, S2CFacadePacket::decode, S2CFacadePacket::handle);
    }

    /** Sends a packet to every player currently tracking the chunk that contains {@code pos}. */
    public static void sendToTracking(ServerLevel level, BlockPos pos, S2CFacadePacket packet) {
        ((ServerChunkCache) level.getChunkSource()).chunkMap
                .getPlayers(new ChunkPos(pos), false)
                .forEach(player -> CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet));
    }

    public static void sendTo(ServerPlayer player, S2CFacadePacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
}
