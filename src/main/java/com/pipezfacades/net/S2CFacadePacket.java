package com.pipezfacades.net;

import com.pipezfacades.ClientFacadeStore;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server → client facade sync. Each entry addresses one (position, side):
 * <ul>
 *     <li>{@code side == SIDE_CLEAR_ALL} — clear every facade at the position;</li>
 *     <li>{@code stateId == REMOVE} — remove the facade on that side;</li>
 *     <li>otherwise — set the facade on that side to the given block-state id.</li>
 * </ul>
 * A single install/remove sends one entry; an initial chunk sync sends many.
 */
public class S2CFacadePacket {

    /** Sentinel state id meaning "remove the facade on this side". */
    public static final int REMOVE = -1;
    /** Sentinel side meaning "clear all sides at this position". */
    public static final int SIDE_CLEAR_ALL = -1;

    public record Entry(BlockPos pos, int side, int stateId) {
    }

    private final List<Entry> entries;

    public S2CFacadePacket(List<Entry> entries) {
        this.entries = entries;
    }

    public static void encode(S2CFacadePacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.entries.size());
        for (Entry e : packet.entries) {
            buf.writeBlockPos(e.pos());
            buf.writeByte(e.side());
            // +1 keeps the value non-negative for writeVarInt (REMOVE == -1 -> 0).
            buf.writeVarInt(e.stateId() + 1);
        }
    }

    public static S2CFacadePacket decode(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            BlockPos pos = buf.readBlockPos();
            int side = buf.readByte();
            int stateId = buf.readVarInt() - 1;
            entries.add(new Entry(pos, side, stateId));
        }
        return new S2CFacadePacket(entries);
    }

    public static void handle(S2CFacadePacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            for (Entry e : packet.entries) {
                ClientFacadeStore.apply(e.pos(), e.side(), e.stateId());
            }
            // Facades are baked into the chunk mesh (FacadedPipeModel) — trigger a section rebuild.
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                for (Entry e : packet.entries) {
                    com.pipezfacades.client.ClientEvents.markFacadeDirty(e.pos());
                }
            });
        });
        ctx.get().setPacketHandled(true);
    }
}
