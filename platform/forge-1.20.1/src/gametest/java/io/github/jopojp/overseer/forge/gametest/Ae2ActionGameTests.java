package io.github.jopojp.overseer.forge.gametest;

import appeng.api.config.Actionable;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.features.IPlayerRegistry;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.blockentity.crafting.CraftingBlockEntity;
import appeng.blockentity.crafting.PatternProviderBlockEntity;
import appeng.blockentity.networking.ControllerBlockEntity;
import appeng.blockentity.storage.DriveBlockEntity;
import appeng.core.definitions.AEItems;
import com.google.gson.JsonParser;
import io.github.jopojp.overseer.core.action.ActionHandler;
import io.github.jopojp.overseer.core.action.ActionRequest;
import io.github.jopojp.overseer.core.action.ActionResult.Status;
import io.github.jopojp.overseer.core.action.Actor;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.core.time.Timestamp;
import io.github.jopojp.overseer.forge.integration.ae2.Ae2Actions;
import io.github.jopojp.overseer.forge.integration.ae2.Ae2Mapping;
import io.github.jopojp.overseer.forge.integration.ae2.Ae2Provider;
import io.github.jopojp.overseer.forge.integration.teams.Teams;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Runs the AE2 world actions (ADR-0017) against real AE2 blocks: crafting CPUs with a real job,
 * drives, refusals. {@code ./gradlew :platform:forge-1.20.1:runGameTestServer -PgametestSuite=ae2}
 */
@PrefixGameTestTemplate(false)
public final class Ae2ActionGameTests {
    private static final UUID OWNER = UUID.fromString("0e1a5ee7-0000-4000-8000-0000000000e1");
    private static final UUID STRANGER = UUID.fromString("0e1a5ee7-0000-4000-8000-0000000000e2");
    private static final Actor OWNER_ACTOR = new Actor(OWNER, "Owner", false);
    private static final Actor STRANGER_ACTOR = new Actor(STRANGER, "Stranger", false);
    private static final Actor OPERATOR = new Actor(STRANGER, "Operator", true);
    private static final Timestamp AT = new Timestamp(Instant.EPOCH, 0, 1);

    private static final BlockPos CONTROLLER = new BlockPos(1, 1, 1);
    private static final BlockPos DRIVE = new BlockPos(2, 1, 1);
    private static final BlockPos CPU = new BlockPos(3, 1, 1);
    private static final BlockPos PROVIDER = new BlockPos(4, 1, 1);

    private Ae2ActionGameTests() {}

    /**
     * A job that never finishes: a processing pattern (stick to diamond) on a provider with no
     * machine next to it. Cancel, rename and mode run against it; strangers are refused.
     */
    @GameTest(template = "empty", templateNamespace = "overseer", timeoutTicks = 300)
    public static void cpuActionsChangeTheRealCpu(GameTestHelper helper) {
        buildGrid(helper);
        Future<ICraftingPlan>[] plan = newPlanHolder();
        helper.runAfterDelay(30, () -> {
            IGrid grid = grid(helper);
            long inserted = grid.getStorageService().getInventory().insert(AEItemKey.of(Items.STICK), 16,
                    Actionable.MODULATE, IActionSource.empty());
            helper.assertTrue(inserted == 16, "sticks inserted: " + inserted);
        });
        // The grid boots before the provider's patterns count; plan once the diamond is craftable.
        helper.runAfterDelay(60, () -> {
            IGrid grid = grid(helper);
            helper.assertTrue(grid.getCraftingService().isCraftable(AEItemKey.of(Items.DIAMOND)), "pattern not active");
            plan[0] = grid.getCraftingService().beginCraftingCalculation(helper.getLevel(), requester(helper),
                    AEItemKey.of(Items.DIAMOND), 4, CalculationStrategy.REPORT_MISSING_ITEMS);
        });
        helper.runAfterDelay(80, () -> {
            ICraftingPlan done = get(helper, plan[0]);
            helper.assertTrue(done.missingItems().isEmpty(), "plan misses " + describe(done.missingItems())
                    + ", craftable: " + grid(helper).getCraftingService().isCraftable(AEItemKey.of(Items.DIAMOND))
                    + ", sticks: " + grid(helper).getStorageService().getCachedInventory().get(AEItemKey.of(Items.STICK)));
            var submitted = grid(helper).getCraftingService().submitJob(done, null, null, true, IActionSource.empty());
            helper.assertTrue(submitted.successful(), "job not started: " + submitted.errorCode());
        });
        helper.runAfterDelay(100, () -> {
            var provider = provider(helper);
            provider.collect(AT);
            ActionHandler actions = provider.actions();
            ResourceRef gridRef = gridRef(helper);
            String cpu = id(helper, CPU);
            var cluster = ((CraftingBlockEntity) helper.getBlockEntity(CPU)).getCluster();
            helper.assertTrue(cluster.isBusy(), "the CPU must be crafting");

            helper.assertTrue(actions.canPerform(OWNER, gridRef, Ae2Actions.CPU_CANCEL), "owners may act");
            helper.assertFalse(actions.canPerform(STRANGER, gridRef, Ae2Actions.CPU_CANCEL), "strangers may not");
            var denied = actions.perform(STRANGER_ACTOR, request(Ae2Actions.CPU_CANCEL, gridRef, "{\"cpu\":\"" + cpu + "\"}"));
            helper.assertTrue(denied.status() == Status.DENIED, "stranger: " + denied);
            helper.assertTrue(cluster.isBusy(), "a refused cancel changes nothing");

            var edit = actions.perform(OWNER_ACTOR, request(Ae2Actions.CPU_EDIT, gridRef,
                    "{\"cpu\":\"" + cpu + "\",\"name\":\"§5Circuits\",\"mode\":\"player_only\"}"));
            helper.assertTrue(edit.isOk() && edit.changes().size() == 2, "edit: " + edit);
            helper.assertTrue("§5Circuits".equals(cluster.getName().getString()), "name: " + cluster.getName());
            helper.assertTrue(cluster.getSelectionMode() == appeng.api.config.CpuSelectionMode.PLAYER_ONLY,
                    "mode: " + cluster.getSelectionMode());
            var attrs = provider.collect(AT).resources().get(Ae2Mapping.cpu(cpu));
            helper.assertTrue("§5Circuits".equals(attrs.get("name")) && "player_only".equals(attrs.get("mode")),
                    "snapshot: " + attrs);
            var cleared = actions.perform(OPERATOR, request(Ae2Actions.CPU_EDIT, gridRef,
                    "{\"cpu\":\"" + cpu + "\",\"name\":\"\"}"));
            helper.assertTrue(cleared.isOk() && cluster.getName() == null, "operators may clear the name: " + cleared);

            boolean suspendable = hasMethod(cluster.craftingLogic.getClass(), "setJobSuspended");
            helper.assertTrue(actions.types().contains(Ae2Actions.CPU_SUSPEND) == suspendable,
                    "suspend is offered exactly where AE2 can do it");

            var cancel = actions.perform(OWNER_ACTOR, request(Ae2Actions.CPU_CANCEL, gridRef, "{\"cpu\":\"" + cpu + "\"}"));
            helper.assertTrue(cancel.isOk() && "item:minecraft:diamond".equals(cancel.changes().get(0).before()),
                    "cancel: " + cancel);
            helper.assertFalse(cluster.isBusy(), "the job must be gone");
            var again = actions.perform(OWNER_ACTOR, request(Ae2Actions.CPU_CANCEL, gridRef, "{\"cpu\":\"" + cpu + "\"}"));
            helper.assertTrue(again.status() == Status.INVALID, "nothing left to cancel: " + again);
            helper.succeed();
        });
    }

    /** Drive priority goes through AE2's own setter; parts of other grids are refused. */
    @GameTest(template = "empty", templateNamespace = "overseer", timeoutTicks = 200)
    public static void drivePriorityIsSet(GameTestHelper helper) {
        buildGrid(helper);
        place(helper, new BlockPos(1, 1, 4), "drive"); // a drive of its own, no grid with a controller
        helper.runAfterDelay(30, () -> {
            var provider = provider(helper);
            provider.collect(AT);
            ActionHandler actions = provider.actions();
            ResourceRef gridRef = gridRef(helper);
            String drive = id(helper, DRIVE);

            var ok = actions.perform(OWNER_ACTOR, request(Ae2Actions.DRIVE_EDIT, gridRef,
                    "{\"drive\":\"" + drive + "\",\"priority\":5}"));
            helper.assertTrue(ok.isOk() && ok.changes().size() == 1, "priority: " + ok);
            helper.assertTrue(((DriveBlockEntity) helper.getBlockEntity(DRIVE)).getPriority() == 5, "AE2 has priority 5");
            helper.assertTrue("5".equals(provider.collect(AT).resources().get(Ae2Mapping.drive(drive)).get("priority")),
                    "the snapshot shows it");

            var other = actions.perform(OWNER_ACTOR, request(Ae2Actions.DRIVE_EDIT, gridRef,
                    "{\"drive\":\"" + id(helper, new BlockPos(1, 1, 4)) + "\",\"priority\":1}"));
            helper.assertTrue(other.status() == Status.INVALID, "drive of another grid: " + other);
            var nothing = actions.perform(OWNER_ACTOR, request(Ae2Actions.DRIVE_EDIT, gridRef,
                    "{\"drive\":\"" + id(helper, new BlockPos(6, 1, 6)) + "\",\"priority\":1}"));
            helper.assertTrue(nothing.status() == Status.NOT_FOUND, "no drive there: " + nothing);
            var wrongGrid = actions.perform(OWNER_ACTOR, request(Ae2Actions.DRIVE_EDIT,
                    Ae2Mapping.grid(id(helper, DRIVE)), "{\"drive\":\"" + drive + "\",\"priority\":1}"));
            helper.assertTrue(wrongGrid.status() == Status.NOT_FOUND, "a drive does not identify a grid: " + wrongGrid);
            helper.succeed();
        });
    }

    /**
     * Pattern providers, a block and a part on a cable: resources with their patterns, every
     * setting changed through the action, the icon also in AE2's terminal group (mixin).
     */
    @GameTest(template = "empty", templateNamespace = "overseer", timeoutTicks = 200)
    public static void patternProvidersAreReadAndEdited(GameTestHelper helper) {
        buildGrid(helper);
        BlockPos cable = new BlockPos(1, 1, 2);
        helper.runAfterDelay(5, () -> {
            var level = helper.getLevel();
            BlockPos at = helper.absolutePos(cable);
            appeng.api.parts.PartHelper.setPart(level, at, null, null,
                    appeng.core.definitions.AEParts.GLASS_CABLE.item(appeng.api.util.AEColor.TRANSPARENT));
            var part = appeng.api.parts.PartHelper.setPart(level, at, net.minecraft.core.Direction.SOUTH, null,
                    appeng.core.definitions.AEParts.PATTERN_PROVIDER.asItem());
            helper.assertTrue(part != null, "provider part not placed");
        });
        helper.runAfterDelay(40, () -> {
            var provider = provider(helper);
            var snapshot = provider.collect(AT);
            ResourceRef gridRef = gridRef(helper);
            String block = id(helper, PROVIDER);
            String part = id(helper, cable) + "/south";
            var attrs = snapshot.resources().get(Ae2Mapping.provider(block));
            helper.assertTrue(attrs != null, "block provider missing; got " + snapshot.resources().keySet());
            helper.assertTrue("ae2:pattern_provider".equals(attrs.get("block")) && "1".equals(attrs.get("patterns")),
                    "block provider: " + attrs);
            helper.assertTrue("none".equals(attrs.get("locked")) && "true".equals(attrs.get("visible")), "state: " + attrs);
            var partAttrs = snapshot.resources().get(Ae2Mapping.provider(part));
            helper.assertTrue(partAttrs != null && "ae2:cable_pattern_provider".equals(partAttrs.get("block")),
                    "part provider: " + partAttrs + " in " + snapshot.resources().keySet());
            helper.assertTrue(gridRef.equals(provider.access().parent(Ae2Mapping.provider(part))), "parts belong to the grid");
            var rows = snapshot.details().get(gridRef);
            helper.assertTrue(rows != null && rows.size() == 1, "pattern rows: " + rows);
            helper.assertTrue("item:minecraft:diamond".equals(rows.get(0).get("output"))
                    && rows.get(0).get("inputs").contains("item:minecraft:stick") && block.equals(rows.get(0).get("provider")),
                    "pattern: " + rows.get(0));
            helper.assertTrue(provider.collect(AT).details().get(gridRef) != null, "patterns stay between scans");

            ActionHandler actions = provider.actions();
            var denied = actions.perform(STRANGER_ACTOR, request(Ae2Actions.PROVIDER_EDIT, gridRef,
                    "{\"provider\":\"" + block + "\",\"priority\":1}"));
            helper.assertTrue(denied.status() == Status.DENIED, "stranger: " + denied);
            var edit = actions.perform(OWNER_ACTOR, request(Ae2Actions.PROVIDER_EDIT, gridRef, "{\"provider\":\"" + block
                    + "\",\"name\":\"§6Assembler HV\",\"priority\":3,\"blocking\":true,\"lock\":\"lock_until_result\","
                    + "\"visible\":false,\"icon\":\"minecraft:diamond\"}"));
            helper.assertTrue(edit.isOk() && edit.changes().size() == 6, "edit: " + edit);
            var host = (PatternProviderBlockEntity) helper.getBlockEntity(PROVIDER);
            var config = host.getLogic().getConfigManager();
            helper.assertTrue("§6Assembler HV".equals(host.getCustomName().getString()), "name: " + host.getCustomName());
            helper.assertTrue(host.getPriority() == 3, "priority: " + host.getPriority());
            helper.assertTrue(config.getSetting(appeng.api.config.Settings.BLOCKING_MODE) == appeng.api.config.YesNo.YES
                    && config.getSetting(appeng.api.config.Settings.PATTERN_ACCESS_TERMINAL) == appeng.api.config.YesNo.NO
                    && config.getSetting(appeng.api.config.Settings.LOCK_CRAFTING_MODE)
                            == appeng.api.config.LockCraftingMode.LOCK_UNTIL_RESULT, "settings");
            var group = host.getLogic().getTerminalGroup();
            helper.assertTrue(AEItemKey.of(Items.DIAMOND).equals(group.icon()), "the terminal shows the chosen icon: " + group);
            helper.assertTrue("§6Assembler HV".equals(group.name().getString()), "and AE2's name: " + group.name());
            attrs = provider.collect(AT).resources().get(Ae2Mapping.provider(block));
            helper.assertTrue("minecraft:diamond".equals(attrs.get("icon")) && "item:minecraft:diamond".equals(attrs.get("groupIcon"))
                    && "lock_until_result".equals(attrs.get("lock")), "snapshot: " + attrs);

            var cleared = actions.perform(OWNER_ACTOR, request(Ae2Actions.PROVIDER_EDIT, gridRef,
                    "{\"provider\":\"" + block + "\",\"icon\":\"\",\"name\":\"\"}"));
            helper.assertTrue(cleared.isOk() && cleared.changes().size() == 2, "clear: " + cleared);
            helper.assertFalse(AEItemKey.of(Items.DIAMOND).equals(host.getLogic().getTerminalGroup().icon()), "AE2's icon is back");
            helper.assertTrue(host.getCustomName() == null, "name removed");
            var unknown = actions.perform(OWNER_ACTOR, request(Ae2Actions.PROVIDER_EDIT, gridRef,
                    "{\"provider\":\"" + block + "\",\"icon\":\"minecraft:no_such_item\"}"));
            helper.assertTrue(unknown.status() == Status.INVALID, "unknown item: " + unknown);

            var partEdit = actions.perform(OWNER_ACTOR, request(Ae2Actions.PROVIDER_EDIT, gridRef,
                    "{\"provider\":\"" + part + "\",\"name\":\"§aSmelter\",\"icon\":\"minecraft:stick\"}"));
            helper.assertTrue(partEdit.isOk() && partEdit.changes().size() == 2, "part: " + partEdit);
            var placed = (appeng.parts.crafting.PatternProviderPart) appeng.api.parts.PartHelper.getPart(helper.getLevel(),
                    helper.absolutePos(cable), net.minecraft.core.Direction.SOUTH);
            helper.assertTrue("§aSmelter".equals(placed.getCustomName().getString()), "part name: " + placed.getCustomName());
            helper.assertTrue(AEItemKey.of(Items.STICK).equals(placed.getLogic().getTerminalGroup().icon()), "part icon");
            helper.succeed();
        });
    }

    /**
     * Remote crafting (ADR-0019): a plan comes back with a later collection, a plan with missing
     * items cannot start, a good one starts on the chosen CPU once; strangers and unknown items
     * are refused; the server setting hides it.
     */
    @GameTest(template = "empty", templateNamespace = "overseer", timeoutTicks = 300)
    public static void remoteCraftingPlansAndStartsAJob(GameTestHelper helper) {
        buildGrid(helper);
        var provider = new Ae2Provider[1];
        var good = new java.util.concurrent.CompletableFuture[1];
        var short_ = new java.util.concurrent.CompletableFuture[1];
        helper.runAfterDelay(30, () -> {
            long inserted = grid(helper).getStorageService().getInventory().insert(AEItemKey.of(Items.STICK), 16,
                    Actionable.MODULATE, IActionSource.empty());
            helper.assertTrue(inserted == 16, "sticks inserted: " + inserted);
        });
        helper.runAfterDelay(60, () -> {
            provider[0] = provider(helper);
            provider[0].collect(AT);
            ActionHandler actions = provider[0].actions();
            ResourceRef gridRef = gridRef(helper);
            helper.assertTrue(actions.types().containsAll(Ae2Actions.CRAFT_TYPES), "crafting is on by default");
            var off = provider(helper).remoteCrafting(() -> false);
            helper.assertFalse(off.actions().types().contains(Ae2Actions.CRAFT_PLAN), "the server setting hides it");

            var unknown = actions.start(OWNER_ACTOR, request(Ae2Actions.CRAFT_PLAN, gridRef,
                    "{\"key\":\"item:minecraft:gold_ingot\",\"amount\":1}")).toCompletableFuture();
            helper.assertTrue(unknown.isDone() && unknown.join().status() == Status.INVALID, "no pattern: " + unknown.getNow(null));
            var stranger = actions.start(STRANGER_ACTOR, request(Ae2Actions.CRAFT_PLAN, gridRef,
                    "{\"key\":\"item:minecraft:diamond\",\"amount\":1}")).toCompletableFuture();
            helper.assertTrue(stranger.isDone() && stranger.join().status() == Status.DENIED, "stranger: " + stranger.getNow(null));

            good[0] = actions.start(OWNER_ACTOR, request(Ae2Actions.CRAFT_PLAN, gridRef,
                    "{\"key\":\"item:minecraft:diamond\",\"amount\":4}")).toCompletableFuture();
            short_[0] = actions.start(OWNER_ACTOR, request(Ae2Actions.CRAFT_PLAN, gridRef,
                    "{\"key\":\"item:minecraft:diamond\",\"amount\":100}")).toCompletableFuture();
            helper.assertFalse(good[0].isDone(), "AE2 plans on its own thread; the answer comes later");
        });
        helper.runAfterDelay(90, () -> {
            provider[0].collect(AT); // hands finished plans back
            helper.assertTrue(good[0].isDone() && short_[0].isDone(), "plans must be done after a collection");
            var plan = (io.github.jopojp.overseer.core.action.ActionResult) good[0].join();
            helper.assertTrue(plan.isOk() && plan.data() != null, "plan: " + plan);
            var data = plan.data();
            helper.assertFalse(data.get("missing").getAsBoolean(), "nothing missing: " + data);
            helper.assertTrue(data.get("bytes").getAsLong() > 0, "bytes: " + data);
            helper.assertTrue(data.get("used").toString().contains("item:minecraft:stick"), "uses sticks: " + data);
            var cpus = data.getAsJsonArray("cpus");
            helper.assertTrue(cpus.size() == 1 && cpus.get(0).getAsJsonObject().get("fits").getAsBoolean(), "cpus: " + cpus);
            String cpu = cpus.get(0).getAsJsonObject().get("id").getAsString();
            helper.assertTrue(id(helper, CPU).equals(cpu), "cpu id like the snapshot: " + cpu);

            var missing = (io.github.jopojp.overseer.core.action.ActionResult) short_[0].join();
            helper.assertTrue(missing.isOk() && missing.data().get("missing").getAsBoolean()
                    && missing.data().get("missingItems").toString().contains("item:minecraft:stick"), "short: " + missing);
            ActionHandler actions = provider[0].actions();
            ResourceRef gridRef = gridRef(helper);
            var refused = actions.perform(OWNER_ACTOR, request(Ae2Actions.CRAFT_START, gridRef,
                    "{\"plan\":\"" + missing.data().get("plan").getAsString() + "\"}"));
            helper.assertTrue(refused.status() == Status.INVALID, "a plan with missing items cannot start: " + refused);

            String id = data.get("plan").getAsString();
            var other = actions.perform(new Actor(UUID.randomUUID(), "Other", false), request(Ae2Actions.CRAFT_START, gridRef,
                    "{\"plan\":\"" + id + "\"}"));
            helper.assertTrue(other.status() == Status.DENIED, "someone else's plan: " + other);
            var started = actions.perform(OWNER_ACTOR, request(Ae2Actions.CRAFT_START, gridRef,
                    "{\"plan\":\"" + id + "\",\"cpu\":\"" + cpu + "\"}"));
            helper.assertTrue(started.isOk() && started.changes().size() == 1
                    && "item:minecraft:diamond".equals(started.changes().get(0).after()), "start: " + started);
            helper.assertTrue(((CraftingBlockEntity) helper.getBlockEntity(CPU)).getCluster().isBusy(), "the CPU crafts");
            var again = actions.perform(OWNER_ACTOR, request(Ae2Actions.CRAFT_START, gridRef, "{\"plan\":\"" + id + "\"}"));
            helper.assertTrue(again.status() == Status.INVALID, "a plan starts once: " + again);
            helper.succeed();
        });
    }

    /**
     * A network without controller has eight channels: ten drives leave devices without one. They
     * are counted and listed with position and item.
     */
    @GameTest(template = "empty", templateNamespace = "overseer", timeoutTicks = 200)
    public static void devicesWithoutChannelAreListed(GameTestHelper helper) {
        BlockPos cell = new BlockPos(0, 1, 5);
        place(helper, cell, "creative_energy_cell");
        for (int x = 1; x <= 10; x++) place(helper, new BlockPos(x, 1, 5), "drive");
        String gridId = id(helper, cell);
        helper.runAfterDelay(60, () -> {
            var provider = new Ae2Provider(helper.getLevel().getServer(), Teams.detect(), () -> Map.of(gridId, OWNER));
            var snapshot = provider.collect(AT);
            ResourceRef ref = Ae2Mapping.grid(gridId);
            double count = snapshot.samples().stream()
                    .filter(x -> x.resource().equals(ref) && x.metric().equals("no_channel"))
                    .mapToDouble(io.github.jopojp.overseer.core.provider.Sample::value).findFirst().orElse(-1);
            helper.assertTrue(count > 0, "devices without channel: " + count + " in " + snapshot.resources().keySet());
            var rows = snapshot.details().get(ref).stream().filter(r -> "offline".equals(r.get("row"))).toList();
            helper.assertTrue(rows.size() == (int) count, "rows: " + rows.size());
            helper.assertTrue("item:ae2:drive".equals(rows.get(0).get("item")) && rows.get(0).get("id").contains("@"),
                    "row: " + rows.get(0));
            var next = provider.collect(AT);
            helper.assertTrue(next.samples().stream().anyMatch(x -> x.resource().equals(ref) && x.metric().equals("no_channel")),
                    "the count stays between scans");
            helper.succeed();
        });
    }

    /** Import and export buses on a cable: what they face, filter, cards, settings. */
    @GameTest(template = "empty", templateNamespace = "overseer", timeoutTicks = 200)
    public static void busesAreReadWithTheirSettings(GameTestHelper helper) {
        buildGrid(helper);
        BlockPos cable = new BlockPos(1, 1, 2);
        helper.setBlock(new BlockPos(1, 1, 3), net.minecraft.world.level.block.Blocks.CHEST);
        helper.setBlock(new BlockPos(2, 1, 2), net.minecraft.world.level.block.Blocks.FURNACE);
        helper.runAfterDelay(5, () -> {
            var level = helper.getLevel();
            BlockPos at = helper.absolutePos(cable);
            appeng.api.parts.PartHelper.setPart(level, at, null, null,
                    appeng.core.definitions.AEParts.GLASS_CABLE.item(appeng.api.util.AEColor.TRANSPARENT));
            var export = appeng.api.parts.PartHelper.setPart(level, at, net.minecraft.core.Direction.SOUTH, null,
                    appeng.core.definitions.AEParts.EXPORT_BUS.asItem());
            var importBus = appeng.api.parts.PartHelper.setPart(level, at, net.minecraft.core.Direction.EAST, null,
                    appeng.core.definitions.AEParts.IMPORT_BUS.asItem());
            helper.assertTrue(export != null && importBus != null, "buses not placed");
            export.getConfig().setStack(0, new GenericStack(AEItemKey.of(Items.IRON_INGOT), 1));
            export.getUpgrades().addItems(new net.minecraft.world.item.ItemStack(AEItems.SPEED_CARD.asItem(), 2));
        });
        helper.runAfterDelay(40, () -> {
            var snapshot = provider(helper).collect(AT);
            String side = id(helper, cable);
            var export = snapshot.resources().get(Ae2Mapping.bus(side + "/south"));
            helper.assertTrue(export != null, "export bus missing; got " + snapshot.resources().keySet());
            helper.assertTrue("export".equals(export.get("kind")) && "ae2:export_bus".equals(export.get("block")), "export: " + export);
            helper.assertTrue("minecraft:chest".equals(export.get("targetBlock")) && "Chest".equals(export.get("target")),
                    "faces the chest: " + export);
            helper.assertTrue(export.get("filter").contains("item:minecraft:iron_ingot"), "filter: " + export.get("filter"));
            helper.assertTrue("ae2:speed_card=2".equals(export.get("upgrades")), "upgrades: " + export.get("upgrades"));
            helper.assertTrue("false".equals(export.get("craftOnly")) && "default".equals(export.get("scheduling")),
                    "export settings: " + export);
            var importBus = snapshot.resources().get(Ae2Mapping.bus(side + "/east"));
            helper.assertTrue(importBus != null && "import".equals(importBus.get("kind"))
                    && "minecraft:furnace".equals(importBus.get("targetBlock")) && "".equals(importBus.get("craftOnly")),
                    "import: " + importBus);
            helper.succeed();
        });
    }

    // --- setup -----------------------------------------------------------------------------

    /** Controller (owned), creative power, drive with a 1k cell, 1k CPU, provider with a pattern. */
    private static void buildGrid(GameTestHelper helper) {
        place(helper, CONTROLLER, "controller");
        place(helper, new BlockPos(1, 2, 1), "creative_energy_cell");
        place(helper, DRIVE, "drive");
        place(helper, CPU, "1k_crafting_storage");
        place(helper, PROVIDER, "pattern_provider");
        int owner = IPlayerRegistry.getMapping(helper.getLevel()).getPlayerId(OWNER);
        if (helper.getBlockEntity(CONTROLLER) instanceof ControllerBlockEntity c) c.getMainNode().setOwningPlayerId(owner);
        helper.runAfterDelay(10, () -> {
            ((DriveBlockEntity) helper.getBlockEntity(DRIVE)).getInternalInventory().setItemDirect(0, AEItems.ITEM_CELL_1K.stack());
            var pattern = PatternDetailsHelper.encodeProcessingPattern(
                    new GenericStack[] {new GenericStack(AEItemKey.of(Items.STICK), 1)},
                    new GenericStack[] {new GenericStack(AEItemKey.of(Items.DIAMOND), 1)});
            var rest = ((PatternProviderBlockEntity) helper.getBlockEntity(PROVIDER)).getLogic().getPatternInv().addItems(pattern);
            helper.assertTrue(rest.isEmpty(), "pattern not accepted");
        });
    }

    /** AE2 looks up patterns through the requester's node: without one, nothing is craftable. */
    private static ICraftingSimulationRequester requester(GameTestHelper helper) {
        var node = ((ControllerBlockEntity) helper.getBlockEntity(CONTROLLER)).getMainNode().getNode();
        return new ICraftingSimulationRequester() {
            @Override
            public IActionSource getActionSource() {
                return IActionSource.empty();
            }

            @Override
            public appeng.api.networking.IGridNode getGridNode() {
                return node;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static Future<ICraftingPlan>[] newPlanHolder() {
        return new Future[1];
    }

    private static ICraftingPlan get(GameTestHelper helper, Future<ICraftingPlan> plan) {
        try {
            return plan.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            helper.fail("crafting calculation failed: " + e);
            throw new IllegalStateException(e);
        }
    }

    private static String describe(appeng.api.stacks.KeyCounter counter) {
        var text = new StringBuilder();
        counter.forEach(e -> text.append(e.getKey()).append('x').append(e.getLongValue()).append(' '));
        return text.toString();
    }

    private static Ae2Provider provider(GameTestHelper helper) {
        return new Ae2Provider(helper.getLevel().getServer(), Teams.detect(), Map::of);
    }

    private static IGrid grid(GameTestHelper helper) {
        return ((ControllerBlockEntity) helper.getBlockEntity(CONTROLLER)).getMainNode().getGrid();
    }

    private static ResourceRef gridRef(GameTestHelper helper) {
        return Ae2Mapping.grid(id(helper, CONTROLLER));
    }

    private static String id(GameTestHelper helper, BlockPos relative) {
        BlockPos at = helper.absolutePos(relative);
        return helper.getLevel().dimension().location() + "@" + at.getX() + "," + at.getY() + "," + at.getZ();
    }

    private static ActionRequest request(String type, ResourceRef target, String params) {
        return new ActionRequest(type, target, JsonParser.parseString(params).getAsJsonObject());
    }

    private static boolean hasMethod(Class<?> type, String name) {
        for (var method : type.getMethods()) {
            if (method.getName().equals(name)) return true;
        }
        return false;
    }

    private static void place(GameTestHelper helper, BlockPos relative, String block) {
        Block ae2 = ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryBuild("ae2", block));
        helper.assertTrue(ae2 != null, "unknown AE2 block " + block);
        helper.setBlock(relative, ae2);
    }
}
