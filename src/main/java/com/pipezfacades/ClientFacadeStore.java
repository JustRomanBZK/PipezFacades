package com.pipezfacades;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side mirror of the server's facades, filled by {@code S2CFacadePacket}. Read by the renderer
 * and the grid-highlight overlay.
 *
 * <p>Deliberately contains no client-only types, so it is safe to reference from common code without
 * risking a class-load failure on a dedicated server.
 */
public final class ClientFacadeStore {

    /** value arrays are always length 6, indexed by {@link Direction#ordinal()}. */
    private static final Map<BlockPos, BlockState[]> FACADES = new ConcurrentHashMap<>();

    private ClientFacadeStore() {
    }

    /**
     * Applies a single sync delta.
     *
     * @param side    direction ordinal, or {@code -1} to clear every side at the position
     * @param stateId block-state id, or {@code -1} to remove the facade on that side
     */
    public static void apply(BlockPos pos, int side, int stateId) {
        if (side < 0) {
            FACADES.remove(pos);
            return;
        }
        if (side >= 6) {
            return;
        }
        BlockState[] sides = FACADES.computeIfAbsent(pos.immutable(), p -> new BlockState[6]);
        if (stateId < 0) {
            sides[side] = null;
        } else {
            BlockState state = Block.stateById(stateId);
            sides[side] = state.isAir() ? null : state;
        }
        boolean empty = true;
        for (BlockState s : sides) {
            if (s != null) {
                empty = false;
                break;
            }
        }
        if (empty) {
            FACADES.remove(pos);
        }
    }

    public static Map<BlockPos, BlockState[]> all() {
        return FACADES;
    }

    @Nullable
    public static BlockState get(BlockPos pos, Direction side) {
        BlockState[] sides = FACADES.get(pos);
        return sides == null ? null : sides[side.ordinal()];
    }

    /** The per-side facade states at a position (do not mutate), or null. */
    @Nullable
    public static BlockState[] getSides(BlockPos pos) {
        return FACADES.get(pos);
    }

    public static boolean anyAt(BlockPos pos) {
        return FACADES.containsKey(pos);
    }

    /** Drops every facade whose position lies in the given chunk (called when a client chunk unloads). */
    public static void clearChunk(ChunkPos chunk) {
        FACADES.keySet().removeIf(pos -> (pos.getX() >> 4) == chunk.x && (pos.getZ() >> 4) == chunk.z);
    }

    public static void clear() {
        FACADES.clear();
    }
}
