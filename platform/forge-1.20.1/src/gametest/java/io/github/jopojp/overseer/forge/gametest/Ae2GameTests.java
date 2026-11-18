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
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Runs the AE2 provider against real AE2 blocks.
 * {@code ./gradlew :platform:forge-1.20.1:runGameTestServer}
 */
@GameTestHolder(Overseer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class Ae2GameTests {
    private static final UUID OWNER = UUID.fromString("0e1a5ee7-0000-4000-8000-0000000000c1");
    private static final UUID STRANGER = UUID.fromString("0e1a5ee7-0000-4000-8000-0000000000d2");
    private static final Timestamp AT = new Timestamp(Instant.EPOCH, 0, 1);

    private Ae2GameTests() {}

    @GameTest(template = "empty", timeoutTicks = 200)
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

            long nearby = snapshot.resources().keySet().stream()
                    .filter(r -> r.id().startsWith(dimension + "@"))
                    .filter(r -> !"hidden".equals(snapshot.resources().get(r).get("kind")))
                    .filter(r -> {
                        String[] p = r.id().substring(r.id().indexOf('@') + 1).split(",");
                        return Math.abs(Integer.parseInt(p[0]) - id.getX()) < 16
                                && Math.abs(Integer.parseInt(p[2]) - id.getZ()) < 16;
                    })
                    .count();
            helper.assertTrue(nearby == 1, "small grids without controller must be skipped, got " + nearby);

            helper.assertTrue(provider.access().canView(OWNER, ref), "owner must see the grid");
            helper.assertFalse(provider.access().canView(STRANGER, ref), "stranger must not see the grid");
            helper.succeed();
        });
    }

    /** Five energy cells form a grid without controller: hidden until registered. */
    @GameTest(template = "empty", timeoutTicks = 200)
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
    @GameTest(template = "empty", timeoutTicks = 200)
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
            var provider = new Ae2Provider(helper.getLevel().getServer(), Teams.detect(), java.util.Map::of);
            var snapshot = provider.collect(AT);
            BlockPos id = helper.absolutePos(controller);
            String dimension = helper.getLevel().dimension().location().toString();
            ResourceRef ref = Ae2Mapping.grid(dimension + "@" + id.getX() + "," + id.getY() + "," + id.getZ());
            var stacks = snapshot.inventories().get(ref);
            helper.assertTrue(stacks != null, "first collection scans; got " + snapshot.inventories().keySet());
            var plain = stacks.stream().filter(s -> s.key().equals("item:minecraft:iron_ingot")).findFirst();
            helper.assertTrue(plain.isPresent() && plain.get().amount() == 100, "plain iron: " + stacks);
            helper.assertTrue("item".equals(plain.get().kind()), "kind: " + plain.get().kind());
            var shiny = stacks.stream().filter(s -> s.key().startsWith("item:minecraft:iron_ingot#")).findFirst();
            helper.assertTrue(shiny.isPresent() && shiny.get().amount() == 3, "NBT variant: " + stacks);
            helper.assertTrue(provider.collect(AT).inventories().isEmpty(), "the next nine collections do not scan");
            helper.succeed();
        });
    }

    private static void place(GameTestHelper helper, BlockPos relative, String block) {
        Block ae2 = ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryBuild("ae2", block));
        helper.assertTrue(ae2 != null, "unknown AE2 block " + block);
        helper.setBlock(relative, ae2);
    }
}
