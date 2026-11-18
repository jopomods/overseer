package io.github.jopojp.overseer.forge.gametest;

import appeng.api.features.IPlayerRegistry;
import appeng.blockentity.networking.ControllerBlockEntity;
import io.github.jopojp.overseer.core.Overseer;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.core.provider.Sample;
import io.github.jopojp.overseer.core.time.Timestamp;
import io.github.jopojp.overseer.forge.integration.ae2.Ae2Mapping;
import io.github.jopojp.overseer.forge.integration.ae2.Ae2Provider;
import io.github.jopojp.overseer.forge.integration.teams.Teams;
import java.time.Instant;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Runs the AE2 provider against real AE2 blocks.
 * {@code ./gradlew :platform:forge-1.20.1:runGameTestServer}
 */
@PrefixGameTestTemplate(false)
public final class Ae2GameTests {
    private static final UUID OWNER = UUID.fromString("0e1a5ee7-0000-4000-8000-0000000000c1");
    private static final UUID STRANGER = UUID.fromString("0e1a5ee7-0000-4000-8000-0000000000d2");
    private static final Timestamp AT = new Timestamp(Instant.EPOCH, 0, 1);

    private Ae2GameTests() {}

    @GameTest(template = "empty", templateNamespace = "overseer", timeoutTicks = 200)
    public static void gridWithControllerIsDiscoveredWithOwnerAndPower(GameTestHelper helper) {
        BlockPos first = new BlockPos(1, 1, 1);
        BlockPos second = new BlockPos(2, 1, 1);
        place(helper, first, "controller");
        place(helper, second, "controller");
        place(helper, new BlockPos(1, 2, 1), "creative_energy_cell");
        // A separate grid without controller must stay hidden.
        place(helper, new BlockPos(5, 1, 1), "energy_cell");

        // AE2 maps players to numeric ids; placing by hand would record the placer this way.
        int owner = IPlayerRegistry.getMapping(helper.getLevel()).getPlayerId(OWNER);
        for (BlockPos pos : new BlockPos[] {first, second}) {
            if (helper.getBlockEntity(pos) instanceof ControllerBlockEntity c) c.getMainNode().setOwningPlayerId(owner);
        }

        helper.runAfterDelay(40, () -> {
            var provider = new Ae2Provider(helper.getLevel().getServer(), Teams.detect(), java.util.Map::of);
            var snapshot = provider.collect(AT);
            BlockPos id = helper.absolutePos(first);
            String dimension = helper.getLevel().dimension().location().toString();
            ResourceRef ref = Ae2Mapping.grid(dimension + "@" + id.getX() + "," + id.getY() + "," + id.getZ());

            var attrs = snapshot.resources().get(ref);
            helper.assertTrue(attrs != null, "grid resource missing; got " + snapshot.resources().keySet());
            helper.assertTrue("2".equals(attrs.get("controllers")), "controllers: " + attrs.get("controllers"));
            helper.assertTrue(OWNER.toString().equals(attrs.get("owner")), "owner: " + attrs.get("owner"));
            helper.assertTrue("true".equals(attrs.get("powered")), "creative cell must power the grid");
            double max = snapshot.samples().stream()
                    .filter(s -> s.resource().equals(ref) && s.metric().equals("power_max"))
                    .mapToDouble(Sample::value).findFirst().orElse(-1);
            helper.assertTrue(max > 0, "power_max: " + max);

            // The lone energy cell forms a grid of one node: neither listed nor hidden.
            BlockPos lone = helper.absolutePos(new BlockPos(5, 1, 1));
            ResourceRef loneRef = Ae2Mapping.grid(dimension + "@" + lone.getX() + "," + lone.getY() + "," + lone.getZ());
            helper.assertFalse(snapshot.resources().containsKey(loneRef), "small grids without controller must be skipped");

            helper.assertTrue(provider.access().canView(OWNER, ref), "owner must see the grid");
            helper.assertFalse(provider.access().canView(STRANGER, ref), "stranger must not see the grid");
            helper.succeed();
        });
    }

    /** Five energy cells form a grid without controller: hidden until registered. */
    @GameTest(template = "empty", templateNamespace = "overseer", timeoutTicks = 200)
    public static void gridWithoutControllerIsHiddenUntilRegistered(GameTestHelper helper) {
        for (int x = 0; x < 5; x++) place(helper, new BlockPos(x, 1, 3), "energy_cell");
        BlockPos lowest = helper.absolutePos(new BlockPos(0, 1, 3));
        BlockPos registeredAt = helper.absolutePos(new BlockPos(2, 1, 3));
        String dimension = helper.getLevel().dimension().location().toString();
        String hiddenId = dimension + "@" + lowest.getX() + "," + lowest.getY() + "," + lowest.getZ();
        String registeredId = dimension + "@" + registeredAt.getX() + "," + registeredAt.getY() + "," + registeredAt.getZ();

        helper.runAfterDelay(40, () -> {
            var server = helper.getLevel().getServer();
            var hidden = new Ae2Provider(server, Teams.detect(), java.util.Map::of);
            var snapshot = hidden.collect(AT);
            ResourceRef hiddenRef = Ae2Mapping.grid(hiddenId);
            var attrs = snapshot.resources().get(hiddenRef);
            helper.assertTrue(attrs != null, "hidden grid missing; got " + snapshot.resources().keySet());
            helper.assertTrue("hidden".equals(attrs.get("kind")), "kind: " + attrs.get("kind"));
            helper.assertTrue("5".equals(attrs.get("nodes")), "nodes: " + attrs.get("nodes"));
            helper.assertTrue(snapshot.samples().stream().noneMatch(s -> s.resource().equals(hiddenRef)),
                    "hidden grids have no history");
            helper.assertFalse(hidden.access().canView(OWNER, hiddenRef), "hidden grids are for admins only");

            var registered = new Ae2Provider(server, Teams.detect(), () -> java.util.Map.of(registeredId, OWNER));
            snapshot = registered.collect(AT);
            ResourceRef ref = Ae2Mapping.grid(registeredId);
            attrs = snapshot.resources().get(ref);
            helper.assertTrue(attrs != null, "registered grid missing; got " + snapshot.resources().keySet());
            helper.assertTrue("registered".equals(attrs.get("kind")), "kind: " + attrs.get("kind"));
            helper.assertTrue(OWNER.toString().equals(attrs.get("owner")), "owner: " + attrs.get("owner"));
            helper.assertFalse(snapshot.resources().containsKey(hiddenRef), "a registered grid is not also hidden");
            helper.assertTrue(snapshot.samples().stream().anyMatch(s -> s.resource().equals(ref)), "history samples");
            helper.assertTrue(registered.access().canView(OWNER, ref), "the registering player owns it");
            helper.succeed();
        });
    }

    /** A drive with a 1k cell: the scan lists its contents, NBT variants under their own key. */
    @GameTest(template = "empty", templateNamespace = "overseer", timeoutTicks = 200)
    public static void inventoryScanListsTheContents(GameTestHelper helper) {
        BlockPos controller = new BlockPos(1, 1, 1);
        BlockPos drive = new BlockPos(2, 1, 1);
        place(helper, controller, "controller");
        place(helper, new BlockPos(1, 2, 1), "creative_energy_cell");
        place(helper, drive, "drive");

        helper.runAfterDelay(20, () -> {
            if (!(helper.getBlockEntity(drive) instanceof appeng.blockentity.storage.DriveBlockEntity d)) {
                helper.fail("drive missing");
                return;
            }
            d.getInternalInventory().setItemDirect(0, appeng.core.definitions.AEItems.ITEM_CELL_1K.stack());
        });
        helper.runAfterDelay(40, () -> {
            var grid = ((ControllerBlockEntity) helper.getBlockEntity(controller)).getMainNode().getGrid();
            var named = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT);
            named.setHoverName(net.minecraft.network.chat.Component.literal("Shiny"));
            var storage = grid.getStorageService().getInventory();
            var source = appeng.api.networking.security.IActionSource.empty();
            long plain = storage.insert(appeng.api.stacks.AEItemKey.of(net.minecraft.world.item.Items.IRON_INGOT), 100,
                    appeng.api.config.Actionable.MODULATE, source);
            long shiny = storage.insert(appeng.api.stacks.AEItemKey.of(named), 3, appeng.api.config.Actionable.MODULATE,
                    source);
            helper.assertTrue(plain == 100 && shiny == 3, "inserted " + plain + " and " + shiny);
        });
        helper.runAfterDelay(80, () -> {
            BlockPos id = helper.absolutePos(controller);
            String dimension = helper.getLevel().dimension().location().toString();
            String gridId = dimension + "@" + id.getX() + "," + id.getY() + "," + id.getZ();
            ResourceRef ref = Ae2Mapping.grid(gridId);
            var provider = new Ae2Provider(helper.getLevel().getServer(), Teams.detect(), java.util.Map::of,
                    () -> java.util.Map.of(gridId, java.util.Set.of("item:minecraft:iron_ingot")));
            var snapshot = provider.collect(AT);
            var stacks = snapshot.inventories().get(ref);
            helper.assertTrue(stacks != null, "first collection scans; got " + snapshot.inventories().keySet());
            var plain = stacks.stream().filter(s -> s.key().equals("item:minecraft:iron_ingot")).findFirst();
            helper.assertTrue(plain.isPresent() && plain.get().amount() == 100, "plain iron: " + stacks);
            helper.assertTrue("item".equals(plain.get().kind()), "kind: " + plain.get().kind());
            var shiny = stacks.stream().filter(s -> s.key().startsWith("item:minecraft:iron_ingot#")).findFirst();
            helper.assertTrue(shiny.isPresent() && shiny.get().amount() == 3, "NBT variant: " + stacks);
            BlockPos driveAt = helper.absolutePos(drive);
            ResourceRef driveRef = Ae2Mapping.drive(dimension + "@" + driveAt.getX() + "," + driveAt.getY() + "," + driveAt.getZ());
            var driveAttrs = snapshot.resources().get(driveRef);
            helper.assertTrue(driveAttrs != null, "drive missing; got " + snapshot.resources().keySet());
            helper.assertTrue("ae2:drive".equals(driveAttrs.get("block")), "block: " + driveAttrs.get("block"));
            helper.assertTrue("0=ae2:item_storage_cell_1k=not_empty".equals(driveAttrs.get("cells")),
                    "cells: " + driveAttrs.get("cells"));
            double total = snapshot.samples().stream()
                    .filter(s -> s.resource().equals(driveRef) && s.metric().equals("bytes_total"))
                    .mapToDouble(Sample::value).findFirst().orElse(-1);
            double types = snapshot.samples().stream()
                    .filter(s -> s.resource().equals(driveRef) && s.metric().equals("types_used"))
                    .mapToDouble(Sample::value).findFirst().orElse(-1);
            helper.assertTrue(total == 1024, "bytes_total: " + total);
            helper.assertTrue(types == 2, "types_used: " + types);
            helper.assertTrue(ref.equals(provider.access().parent(driveRef)), "a drive belongs to its grid");
            var next = provider.collect(AT);
            helper.assertTrue(next.inventories().isEmpty(), "the next nine collections do not scan");
            double stock = next.samples().stream()
                    .filter(s -> s.resource().equals(ref) && s.metric().equals("stock:item:minecraft:iron_ingot"))
                    .mapToDouble(Sample::value).findFirst().orElse(-1);
            helper.assertTrue(stock == 100, "watched items are read every collection: " + stock);
            helper.succeed();
        });
    }

    /** A crafting storage block forms a CPU: a resource of its own, visible like its grid. */
    @GameTest(template = "empty", templateNamespace = "overseer", timeoutTicks = 200)
    public static void craftingCpusAreResources(GameTestHelper helper) {
        BlockPos controller = new BlockPos(1, 1, 1);
        BlockPos storage = new BlockPos(2, 1, 1);
        place(helper, controller, "controller");
        place(helper, new BlockPos(1, 2, 1), "creative_energy_cell");
        place(helper, storage, "1k_crafting_storage");
        int owner = IPlayerRegistry.getMapping(helper.getLevel()).getPlayerId(OWNER);
        if (helper.getBlockEntity(controller) instanceof ControllerBlockEntity c) c.getMainNode().setOwningPlayerId(owner);

        helper.runAfterDelay(40, () -> {
            var provider = new Ae2Provider(helper.getLevel().getServer(), Teams.detect(), java.util.Map::of);
            var snapshot = provider.collect(AT);
            String dimension = helper.getLevel().dimension().location().toString();
            BlockPos id = helper.absolutePos(controller);
            BlockPos at = helper.absolutePos(storage);
            ResourceRef grid = Ae2Mapping.grid(dimension + "@" + id.getX() + "," + id.getY() + "," + id.getZ());
            ResourceRef cpu = Ae2Mapping.cpu(dimension + "@" + at.getX() + "," + at.getY() + "," + at.getZ());
            var attrs = snapshot.resources().get(cpu);
            helper.assertTrue(attrs != null, "CPU missing; got " + snapshot.resources().keySet());
            helper.assertTrue(grid.id().equals(attrs.get("grid")), "grid: " + attrs.get("grid"));
            helper.assertTrue("1024".equals(attrs.get("storage")), "storage: " + attrs.get("storage"));
            helper.assertTrue("false".equals(attrs.get("busy")), "busy: " + attrs.get("busy"));
            helper.assertTrue(grid.equals(provider.access().parent(cpu)), "a CPU belongs to its grid");
            helper.succeed();
        });
    }

    /** With placer access on, whoever placed a part of a grid may view it. */
    @GameTest(template = "empty", templateNamespace = "overseer", timeoutTicks = 200)
    public static void placersSeeGridsOnlyWhenEnabled(GameTestHelper helper) {
        BlockPos controller = new BlockPos(1, 1, 1);
        BlockPos cell = new BlockPos(2, 1, 1);
        place(helper, controller, "controller");
        place(helper, cell, "energy_cell");
        var players = IPlayerRegistry.getMapping(helper.getLevel());
        if (helper.getBlockEntity(controller) instanceof ControllerBlockEntity c) {
            c.getMainNode().setOwningPlayerId(players.getPlayerId(OWNER));
        }
        if (helper.getBlockEntity(cell) instanceof appeng.blockentity.networking.EnergyCellBlockEntity e) {
            e.getMainNode().setOwningPlayerId(players.getPlayerId(STRANGER));
        }
        helper.runAfterDelay(40, () -> {
            BlockPos id = helper.absolutePos(controller);
            String dimension = helper.getLevel().dimension().location().toString();
            ResourceRef ref = Ae2Mapping.grid(dimension + "@" + id.getX() + "," + id.getY() + "," + id.getZ());
            var off = new Ae2Provider(helper.getLevel().getServer(), Teams.detect(), java.util.Map::of);
            off.collect(AT);
            helper.assertFalse(off.access().canView(STRANGER, ref), "off by default");
            var on = new Ae2Provider(helper.getLevel().getServer(), Teams.detect(), java.util.Map::of).placerAccess(() -> true);
            on.collect(AT);
            helper.assertTrue(on.access().canView(STRANGER, ref), "the player who placed the energy cell may view");
            helper.assertFalse(on.access().canEdit(STRANGER, ref), "but not edit");
            helper.succeed();
        });
    }

    private static void place(GameTestHelper helper, BlockPos relative, String block) {
        Block ae2 = ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryBuild("ae2", block));
        helper.assertTrue(ae2 != null, "unknown AE2 block " + block);
        helper.setBlock(relative, ae2);
    }
}
