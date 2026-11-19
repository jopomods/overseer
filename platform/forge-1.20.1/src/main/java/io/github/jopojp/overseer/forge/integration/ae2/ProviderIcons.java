package io.github.jopojp.overseer.forge.integration.ae2;

import appeng.api.stacks.AEItemKey;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import appeng.parts.AEBasePart;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Icons of pattern providers chosen from the dashboard (ADR-0018). AE2 has no field for them, so
 * they live in Forge's persistent data of the block entity (the provider block, or the cable bus
 * that holds a provider part), saved and moved with it by Minecraft. AE2 shows them in its Pattern
 * Access Terminal through {@code PatternProviderLogicMixin}. Server thread.
 *
 * <p>Layout: {@code ForgeData.overseer.providerIcons.<block|side> = "<item id>"}.
 */
public final class ProviderIcons {
    private static final String ROOT = "overseer";
    private static final String ICONS = "providerIcons";
    private static final String BLOCK = "block";

    private ProviderIcons() {}

    /** The chosen icon as AE2 key, {@code null} when none is set (or the item no longer exists). */
    public static AEItemKey icon(PatternProviderLogicHost host) {
        Item item = item(host);
        return item == null ? null : AEItemKey.of(item);
    }

    /** Registry id of the chosen icon, {@code null} when none. */
    static String id(PatternProviderLogicHost host) {
        Item item = item(host);
        return item == null ? null : String.valueOf(ForgeRegistries.ITEMS.getKey(item));
    }

    /** Sets ({@code item}) or removes ({@code null}) the icon and marks the block entity changed. */
    static void set(PatternProviderLogicHost host, Item item) {
        BlockEntity be = host.getBlockEntity();
        if (be == null) return;
        CompoundTag data = be.getPersistentData();
        CompoundTag root = data.getCompound(ROOT);
        CompoundTag icons = root.getCompound(ICONS);
        if (item == null) {
            icons.remove(key(host));
        } else {
            icons.putString(key(host), String.valueOf(ForgeRegistries.ITEMS.getKey(item)));
        }
        if (icons.isEmpty()) root.remove(ICONS);
        else root.put(ICONS, icons);
        if (root.isEmpty()) data.remove(ROOT);
        else data.put(ROOT, root);
        be.setChanged();
    }

    private static Item item(PatternProviderLogicHost host) {
        BlockEntity be = host.getBlockEntity();
        if (be == null) return null;
        CompoundTag data = be.getPersistentData();
        if (!data.contains(ROOT, Tag.TAG_COMPOUND)) return null;
        CompoundTag icons = data.getCompound(ROOT).getCompound(ICONS);
        String id = icons.getString(key(host));
        if (id.isEmpty()) return null;
        ResourceLocation location = ResourceLocation.tryParse(id);
        Item item = location == null ? null : ForgeRegistries.ITEMS.getValue(location);
        return item == null || item == Items.AIR ? null : item;
    }

    /** Parts on one cable bus share the block entity: one entry per side. */
    private static String key(PatternProviderLogicHost host) {
        return host instanceof AEBasePart part && part.getSide() != null ? part.getSide().getSerializedName() : BLOCK;
    }
}
