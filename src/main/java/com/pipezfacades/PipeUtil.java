package com.pipezfacades;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

public final class PipeUtil {

    /** Namespace of the pipez mod. All pipez pipes (item/fluid/energy/gas/universal) live here. */
    public static final String PIPEZ_NAMESPACE = "pipez";

    /** NBT key GregTech's facade item stores its block in (see GT's {@code FacadeItemBehaviour}). */
    private static final String GT_FACADE_TAG = "Facade";

    private PipeUtil() {
    }

    /** True if the block is a pipez pipe. Matched by registry namespace to avoid a compile dependency. */
    public static boolean isPipe(BlockState state) {
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return id != null && PIPEZ_NAMESPACE.equals(id.getNamespace());
    }

    /**
     * Resolves the facade block state provided by the held item, or {@code null} if the item is not a
     * facade source. Two sources are accepted:
     * <ol>
     *     <li><b>GregTech facade cover items</b> — any item carrying GT's {@code Facade} NBT compound
     *         (an ItemStack of the camouflage block). This makes GT's crafted facades work on pipez
     *         pipes with no compile dependency on GregTech.</li>
     *     <li><b>Plain block items</b> — any full, self-rendering block.</li>
     * </ol>
     */
    @Nullable
    public static BlockState resolveFacade(ItemStack held) {
        BlockState gt = gtFacadeState(held);
        if (gt != null) {
            return gt;
        }
        return plainBlockState(held);
    }

    /**
     * The facade state carried by a GregTech facade item's NBT, or null. Both known formats of the
     * {@code Facade} tag are supported:
     * <ul>
     *     <li><b>upstream GTCEu Modern 1.20.1</b> — a BlockState via codec: {@code {Name, Properties}};</li>
     *     <li><b>GregTech-Odyssey fork</b> — an ItemStack: {@code {id, Count}}.</li>
     * </ul>
     */
    @Nullable
    public static BlockState gtFacadeState(ItemStack held) {
        CompoundTag tag = held.getTag();
        if (tag == null || !tag.contains(GT_FACADE_TAG, Tag.TAG_COMPOUND)) {
            return null;
        }
        CompoundTag facadeTag = tag.getCompound(GT_FACADE_TAG);
        BlockState state = null;
        if (facadeTag.contains("Name", Tag.TAG_STRING)) {
            // upstream GTCEu: BlockState codec format
            BlockState parsed = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), facadeTag);
            if (!parsed.isAir()) {
                state = parsed;
            }
        } else if (facadeTag.contains("id", Tag.TAG_STRING)) {
            // GregTech-Odyssey fork: ItemStack format
            ItemStack facadeStack = ItemStack.of(facadeTag);
            if (!facadeStack.isEmpty() && facadeStack.getItem() instanceof BlockItem blockItem) {
                state = blockItem.getBlock().defaultBlockState();
            }
        }
        if (state == null) {
            return null;
        }
        return isValidFacadeState(state) ? state : null;
    }

    public static boolean isGtFacadeItem(ItemStack held) {
        return gtFacadeState(held) != null;
    }

    /** The facade state for a plain block item, or null. */
    @Nullable
    public static BlockState plainBlockState(ItemStack held) {
        if (!(held.getItem() instanceof BlockItem blockItem)) {
            return null;
        }
        BlockState state = blockItem.getBlock().defaultBlockState();
        return isValidFacadeState(state) ? state : null;
    }

    /**
     * Mirrors GregTech's {@code FacadeItemBehaviour.isValidFacade}: the block must have no block entity
     * and use a baked model. (Unlike v1 we no longer require {@code canOcclude}, matching GT.)
     */
    public static boolean isValidFacadeState(BlockState state) {
        return !state.hasBlockEntity() && state.getRenderShape() == RenderShape.MODEL;
    }

    /**
     * Whether the held item may place a facade right now. GT facade items work like GT covers — plain
     * right-click. Raw block items require sneaking, so normal building against pipes stays possible.
     */
    public static boolean canPlaceNow(Player player, ItemStack held) {
        if (isGtFacadeItem(held)) {
            return true;
        }
        return plainBlockState(held) != null && player.isShiftKeyDown();
    }
}
