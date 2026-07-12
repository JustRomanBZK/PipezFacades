package com.pipezfacades;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Per-{@link ServerLevel} persistent storage of installed facades: {@code BlockPos -> per-side entries}.
 * Each entry keeps the rendered {@link BlockState} and the exact {@link ItemStack} to return when the
 * facade is removed (so GregTech facade cover items come back as themselves, not as raw blocks).
 */
public class FacadeSavedData extends SavedData {

    private static final String DATA_NAME = "pipezfacades_facades";
    private static final String KEY_LIST = "Facades";
    private static final String KEY_POS = "Pos";
    private static final String KEY_SIDES = "Sides";
    private static final String KEY_SIDE = "Side";
    private static final String KEY_STATE = "State";
    private static final String KEY_DROP = "Drop";

    public record Entry(BlockState state, ItemStack drop) {
    }

    /**
     * value arrays are always length 6, indexed by {@link Direction#ordinal()}. Concurrent map +
     * copy-on-write arrays: collision shapes read this from potentially async entity/pathfinding threads,
     * while writes stay on the server thread.
     */
    private final Map<BlockPos, Entry[]> facades = new ConcurrentHashMap<>();

    public static FacadeSavedData get(ServerLevel level) {
        // 1.20.1 signature: computeIfAbsent(Function<CompoundTag,T> loader, Supplier<T> factory, String name)
        return level.getDataStorage().computeIfAbsent(
                FacadeSavedData::load, FacadeSavedData::new, DATA_NAME);
    }

    public FacadeSavedData() {
    }

    public static FacadeSavedData load(CompoundTag tag) {
        FacadeSavedData data = new FacadeSavedData();
        HolderGetter<Block> blocks = BuiltInRegistries.BLOCK.asLookup();
        ListTag list = tag.getList(KEY_LIST, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag posEntry = list.getCompound(i);
            if (!posEntry.contains(KEY_SIDES, Tag.TAG_LIST)) {
                continue; // tolerate (and drop) the v1 whole-block format
            }
            BlockPos pos = BlockPos.of(posEntry.getLong(KEY_POS));
            Entry[] sides = new Entry[6];
            boolean any = false;
            ListTag sideList = posEntry.getList(KEY_SIDES, Tag.TAG_COMPOUND);
            for (int j = 0; j < sideList.size(); j++) {
                CompoundTag sideTag = sideList.getCompound(j);
                int side = sideTag.getByte(KEY_SIDE);
                if (side < 0 || side >= 6) {
                    continue;
                }
                BlockState state = NbtUtils.readBlockState(blocks, sideTag.getCompound(KEY_STATE));
                if (state.isAir()) {
                    continue;
                }
                ItemStack drop = ItemStack.of(sideTag.getCompound(KEY_DROP));
                sides[side] = new Entry(state, drop);
                any = true;
            }
            if (any) {
                data.facades.put(pos, sides);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<BlockPos, Entry[]> e : facades.entrySet()) {
            CompoundTag posEntry = new CompoundTag();
            posEntry.putLong(KEY_POS, e.getKey().asLong());
            ListTag sideList = new ListTag();
            Entry[] sides = e.getValue();
            for (int i = 0; i < 6; i++) {
                if (sides[i] == null) {
                    continue;
                }
                CompoundTag sideTag = new CompoundTag();
                sideTag.putByte(KEY_SIDE, (byte) i);
                sideTag.put(KEY_STATE, NbtUtils.writeBlockState(sides[i].state()));
                sideTag.put(KEY_DROP, sides[i].drop().save(new CompoundTag()));
                sideList.add(sideTag);
            }
            posEntry.put(KEY_SIDES, sideList);
            list.add(posEntry);
        }
        tag.put(KEY_LIST, list);
        return tag;
    }

    @Nullable
    public Entry get(BlockPos pos, Direction side) {
        Entry[] sides = facades.get(pos);
        return sides == null ? null : sides[side.ordinal()];
    }

    public boolean has(BlockPos pos, Direction side) {
        return get(pos, side) != null;
    }

    /** The per-side entries at a position (do not mutate), or null. Safe to call from any thread. */
    @Nullable
    public Entry[] getSides(BlockPos pos) {
        return facades.get(pos);
    }

    public void set(BlockPos pos, Direction side, Entry entry) {
        Entry[] old = facades.get(pos);
        Entry[] sides = old == null ? new Entry[6] : old.clone();
        sides[side.ordinal()] = entry;
        facades.put(pos.immutable(), sides);
        setDirty();
    }

    /** Removes and returns the facade on one side (or null). Cleans up the position when it empties. */
    @Nullable
    public Entry remove(BlockPos pos, Direction side) {
        Entry[] old = facades.get(pos);
        if (old == null || old[side.ordinal()] == null) {
            return null;
        }
        Entry removed = old[side.ordinal()];
        Entry[] sides = old.clone();
        sides[side.ordinal()] = null;
        boolean empty = true;
        for (Entry e : sides) {
            if (e != null) {
                empty = false;
                break;
            }
        }
        if (empty) {
            facades.remove(pos);
        } else {
            facades.put(pos.immutable(), sides);
        }
        setDirty();
        return removed;
    }

    /** Removes and returns all facades at a position (array indexed by side ordinal), or null. */
    @Nullable
    public Entry[] removeAll(BlockPos pos) {
        Entry[] removed = facades.remove(pos);
        if (removed != null) {
            setDirty();
        }
        return removed;
    }

    /** Visits every facade position in the given chunk (used for initial sync on chunk watch). */
    public void forEachInChunk(ChunkPos chunk, BiConsumer<BlockPos, Entry[]> consumer) {
        for (Map.Entry<BlockPos, Entry[]> e : facades.entrySet()) {
            BlockPos pos = e.getKey();
            if ((pos.getX() >> 4) == chunk.x && (pos.getZ() >> 4) == chunk.z) {
                consumer.accept(pos, e.getValue());
            }
        }
    }
}
