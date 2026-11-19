package io.github.jopojp.overseer.forge.integration.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.core.time.Timestamp;
import io.github.jopojp.overseer.forge.integration.ae2.Ae2Model.ControllerState;
import io.github.jopojp.overseer.forge.integration.ae2.Ae2Model.Crafting;
import io.github.jopojp.overseer.forge.integration.ae2.Ae2Model.Energy;
import io.github.jopojp.overseer.forge.integration.ae2.Ae2Model.Grid;
import io.github.jopojp.overseer.forge.integration.ae2.Ae2Model.Storage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class Ae2MappingTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-0000000000d4");
    private static final Timestamp AT = new Timestamp(Instant.EPOCH, 0, 1);

    private static Grid grid(UUID owner, int x) {
        return new Grid("minecraft:overworld", x, 64, 0, owner, "Owner", 120, 3, ControllerState.CONTROLLER_ONLINE,
                false, 24, new Energy(1_000, 8_000, 35.5, 40, 30, true), new Storage(512, 1_234_567, 4, 64_000, 2),
                new Crafting(2, 1, 65_536, 4));
    }

    @Test
    void mapsGridsWithControllerPosition() {
        var snapshot = Ae2Mapping.snapshot(AT, List.of(grid(OWNER, 10)));
        var ref = new ResourceRef("ae2", "grid", "minecraft:overworld@10,64,0");
        var attrs = snapshot.resources().get(ref);
        assertEquals(OWNER.toString(), attrs.get("owner"));
        assertEquals("Owner", attrs.get("ownerName"));
        assertEquals("controller_online", attrs.get("state"));
        assertEquals("120", attrs.get("nodes"));
        assertEquals("2", attrs.get("cpus"));
        assertTrue(snapshot.samples().stream()
                .anyMatch(s -> s.resource().equals(ref) && s.metric().equals("items") && s.value() == 1_234_567));
        assertTrue(snapshot.samples().stream()
                .anyMatch(s -> s.resource().equals(ref) && s.metric().equals("power_usage") && s.value() == 35.5));
        // no_channel only once the grid was checked (inventory scans).
        assertEquals(Ae2Mapping.METRICS.size() - 1, snapshot.samples().size());
    }

    @Test
    void bootingOrUnpoweredGridsReportNoInventory() {
        Grid g = grid(OWNER, 10);
        var booting = new Grid(g.dimension(), g.x(), g.y(), g.z(), g.owner(), g.ownerName(), g.nodes(), g.controllers(),
                g.state(), true, g.channels(), g.energy(), new Storage(0, 0, 0, 0, 0), g.crafting());
        var unpowered = new Grid(g.dimension(), 11, g.y(), g.z(), g.owner(), g.ownerName(), g.nodes(), g.controllers(),
                g.state(), false, g.channels(), new Energy(0, 8_000, 0, 0, 30, false), new Storage(0, 0, 0, 0, 0),
                g.crafting());
        var snapshot = Ae2Mapping.snapshot(AT, List.of(booting, unpowered));
        assertFalse(snapshot.samples().stream().anyMatch(s -> s.metric().equals("items") || s.metric().equals("fluids")));
        assertTrue(snapshot.samples().stream().anyMatch(s -> s.metric().equals("power_max")));
    }

    private static Grid scanned(Grid g, boolean booting, Ae2Model.Kind kind) {
        return new Grid(g.dimension(), g.x(), g.y(), g.z(), g.owner(), g.ownerName(), g.nodes(), g.controllers(),
                g.state(), booting, g.channels(), g.energy(), g.storage(), g.crafting(), kind,
                List.of(new io.github.jopojp.overseer.core.provider.Stack("item:minecraft:iron_ingot", "item",
                        "Iron Ingot", 64)));
    }

    @Test
    void scannedInventoriesGoIntoTheSnapshotOnlyWhenReal() {
        Grid g = grid(OWNER, 10);
        var snapshot = Ae2Mapping.snapshot(AT, List.of(g));
        assertTrue(snapshot.inventories().isEmpty(), "not scanned this time");

        snapshot = Ae2Mapping.snapshot(AT, List.of(scanned(g, false, Ae2Model.Kind.CONTROLLER)));
        var ref = Ae2Mapping.grid("minecraft:overworld@10,64,0");
        assertEquals(64, snapshot.inventories().get(ref).get(0).amount());

        snapshot = Ae2Mapping.snapshot(AT, List.of(scanned(g, true, Ae2Model.Kind.CONTROLLER),
                scanned(grid(null, 20), false, Ae2Model.Kind.HIDDEN)));
        assertTrue(snapshot.inventories().isEmpty(), "booting and hidden grids have no inventory history");
    }

    @Test
    void cpusAreResourcesWithJobsAndFollowTheirGrid() {
        Grid g = grid(OWNER, 10);
        var line = new io.github.jopojp.overseer.core.provider.JobLine("item:gtceu:resistor", "item", "Resistor", 2, 8,
                120);
        var job = new Ae2Model.Job("item:gtceu:good_electronic_circuit", "item", "Good Electronic Circuit", 64, 1_200,
                300, 90_000_000_000L, List.of(line));
        var cpus = List.of(new Ae2Model.Cpu("minecraft:overworld@1,2,3", "Circuits", 65_536, 4, "any", job, 8),
                new Ae2Model.Cpu("minecraft:overworld@5,2,3", null, 1_024, 0, "any", null));
        var withCpus = new Grid(g.dimension(), g.x(), g.y(), g.z(), g.owner(), g.ownerName(), g.nodes(),
                g.controllers(), g.state(), false, g.channels(), g.energy(), g.storage(),
                new Ae2Model.Crafting(2, 1, 66_560, 4, cpus));
        var snapshot = Ae2Mapping.snapshot(AT, List.of(withCpus));
        var busy = Ae2Mapping.cpu("minecraft:overworld@1,2,3");
        var idle = Ae2Mapping.cpu("minecraft:overworld@5,2,3");
        var attrs = snapshot.resources().get(busy);
        assertEquals("minecraft:overworld@10,64,0", attrs.get("grid"));
        assertEquals("Circuits", attrs.get("name"));
        assertEquals("8", attrs.get("number"), "AE2's own CPU number");
        assertEquals("item:gtceu:good_electronic_circuit", attrs.get("job"));
        assertEquals("64", attrs.get("jobAmount"));
        assertEquals("", snapshot.resources().get(idle).get("job"));
        assertTrue(snapshot.samples().stream().anyMatch(s -> s.resource().equals(busy)
                && s.metric().equals("job_elapsed") && s.value() == 90));
        assertFalse(snapshot.samples().stream().anyMatch(s -> s.resource().equals(idle)), "idle CPUs have no samples");
        assertEquals(List.of(line), snapshot.jobs().get(busy));
        assertFalse(snapshot.jobs().containsKey(idle), "idle CPUs have no job lines");

        var access = Ae2Mapping.AccessIndex.of(List.of(withCpus));
        assertEquals(Ae2Mapping.grid("minecraft:overworld@10,64,0"), access.parent(busy));
        assertEquals(null, access.parent(Ae2Mapping.cpu("minecraft:overworld@9,9,9")));
    }

    @Test
    void drivesAreResourcesWithCellsAndBytes() {
        Grid g = grid(OWNER, 10);
        var drive = new Ae2Model.Drive("minecraft:overworld@4,64,0", "ae2:drive", List.of(
                new Ae2Model.Cell(0, "ae2:item_storage_cell_64k", "not_empty", 1_000, 65_536, 12, 63),
                new Ae2Model.Cell(3, "megacells:item_storage_cell_1m", "full", -1, -1, -1, -1)));
        var withDrive = new Grid(g.dimension(), g.x(), g.y(), g.z(), g.owner(), g.ownerName(), g.nodes(),
                g.controllers(), g.state(), false, g.channels(), g.energy(), g.storage(), g.crafting(),
                Ae2Model.Kind.CONTROLLER, null, List.of(drive));
        var snapshot = Ae2Mapping.snapshot(AT, List.of(withDrive));
        var ref = Ae2Mapping.drive("minecraft:overworld@4,64,0");
        assertEquals("0=ae2:item_storage_cell_64k=not_empty;3=megacells:item_storage_cell_1m=full",
                snapshot.resources().get(ref).get("cells"));
        assertTrue(snapshot.samples().stream().anyMatch(s -> s.resource().equals(ref)
                && s.metric().equals("bytes_total") && s.value() == 65_536), "unknown add-on cells add nothing");
        var gridRef = Ae2Mapping.grid("minecraft:overworld@10,64,0");
        assertTrue(snapshot.samples().stream().anyMatch(s -> s.resource().equals(gridRef)
                && s.metric().equals("bytes_used") && s.value() == 1_000));
        assertEquals(gridRef, Ae2Mapping.AccessIndex.of(List.of(withDrive)).parent(ref));
    }

    @Test
    void watchedItemsBecomeStockMetrics() {
        Grid g = grid(OWNER, 10);
        var watched = new Grid(g.dimension(), g.x(), g.y(), g.z(), g.owner(), g.ownerName(), g.nodes(),
                g.controllers(), g.state(), false, g.channels(), g.energy(), g.storage(), g.crafting(),
                Ae2Model.Kind.CONTROLLER, null, List.of(), java.util.Map.of("item:minecraft:iron_ingot", 42L));
        var snapshot = Ae2Mapping.snapshot(AT, List.of(watched));
        assertTrue(snapshot.samples().stream().anyMatch(s -> s.metric().equals("stock:item:minecraft:iron_ingot")
                && s.value() == 42));
    }

    @Test
    void placersViewOnlyWhenTheServerAllowsIt() {
        Grid g = grid(OWNER, 10);
        var placed = new Grid(g.dimension(), g.x(), g.y(), g.z(), g.owner(), g.ownerName(), g.nodes(),
                g.controllers(), g.state(), false, g.channels(), g.energy(), g.storage(), g.crafting(),
                Ae2Model.Kind.CONTROLLER, null, List.of(), java.util.Map.of(), java.util.Set.of(STRANGER));
        var ref = Ae2Mapping.grid("minecraft:overworld@10,64,0");
        assertFalse(Ae2Mapping.AccessIndex.of(List.of(placed), o -> java.util.Set.of(), false).canView(STRANGER, ref));
        var on = Ae2Mapping.AccessIndex.of(List.of(placed), o -> java.util.Set.of(), true);
        assertTrue(on.canView(STRANGER, ref));
        assertFalse(on.canEdit(STRANGER, ref), "placers never edit");
    }

    @Test
    void hiddenGridsHaveNoHistoryAndNoOwner() {
        Grid g = grid(null, 10);
        var hidden = new Grid(g.dimension(), g.x(), g.y(), g.z(), null, null, g.nodes(), 0, g.state(), false,
                g.channels(), g.energy(), g.storage(), g.crafting(), Ae2Model.Kind.HIDDEN);
        var snapshot = Ae2Mapping.snapshot(AT, List.of(hidden));
        var ref = Ae2Mapping.grid("minecraft:overworld@10,64,0");
        assertEquals("hidden", snapshot.resources().get(ref).get("kind"));
        assertTrue(snapshot.samples().isEmpty());
        assertFalse(Ae2Mapping.AccessIndex.of(List.of(hidden)).canView(OWNER, ref));
    }

    @Test
    void ownersTeamSeesButDoesNotEdit() {
        UUID mate = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
        var access = Ae2Mapping.AccessIndex.of(List.of(grid(OWNER, 10)),
                owner -> owner.equals(OWNER) ? java.util.Set.of(mate) : java.util.Set.of());
        var ref = Ae2Mapping.grid("minecraft:overworld@10,64,0");
        assertTrue(access.canView(mate, ref));
        assertFalse(access.canEdit(mate, ref));
        assertFalse(access.canView(STRANGER, ref));
    }

    @Test
    void onlyOwnersSeeAndEditTheirGrid() {
        var access = Ae2Mapping.AccessIndex.of(List.of(grid(OWNER, 10), grid(null, 20)));
        var owned = Ae2Mapping.grid("minecraft:overworld@10,64,0");
        var orphan = Ae2Mapping.grid("minecraft:overworld@20,64,0");
        assertTrue(access.canView(OWNER, owned));
        assertTrue(access.canEdit(OWNER, owned));
        assertFalse(access.canView(STRANGER, owned));
        assertFalse(access.canView(OWNER, orphan), "grids without known owner are admin-only");
        assertFalse(access.canView(OWNER, new ResourceRef("flux", "grid", "minecraft:overworld@10,64,0")));
        assertFalse(Ae2Mapping.AccessIndex.EMPTY.canView(OWNER, owned));
    }

    @Test
    void ownerAndPartyActButPlacersAndStrangersDoNot() {
        UUID mate = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
        UUID placer = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
        Grid g = grid(OWNER, 10);
        var placed = new Grid(g.dimension(), g.x(), g.y(), g.z(), g.owner(), g.ownerName(), g.nodes(),
                g.controllers(), g.state(), false, g.channels(), g.energy(), g.storage(), g.crafting(),
                Ae2Model.Kind.CONTROLLER, null, List.of(), java.util.Map.of(), java.util.Set.of(placer));
        var access = Ae2Mapping.AccessIndex.of(List.of(placed, grid(null, 20)),
                owner -> owner.equals(OWNER) ? java.util.Set.of(mate) : java.util.Set.of(), true);
        var ref = Ae2Mapping.grid("minecraft:overworld@10,64,0");
        assertTrue(access.canAct(OWNER, ref));
        assertTrue(access.canAct(mate, ref), "the owner's party may operate the grid (ADR-0017)");
        assertTrue(access.canView(placer, ref));
        assertFalse(access.canAct(placer, ref), "placers only view");
        assertFalse(access.canAct(STRANGER, ref));
        assertFalse(access.canAct(OWNER, Ae2Mapping.grid("minecraft:overworld@20,64,0")), "no owner: operators only");
        assertFalse(access.canAct(OWNER, Ae2Mapping.cpu("minecraft:overworld@10,64,0")), "actions target grids");
    }

    @Test
    void suspendedJobsAndDrivePrioritiesAreAttributes() {
        var job = new Ae2Model.Job("item:minecraft:stick", "item", "Stick", 64, 100, 10, 1_000_000_000L);
        var cpus = List.of(new Ae2Model.Cpu("minecraft:overworld@1,2,3", null, 1_024, 0, "any", job, 1, true),
                new Ae2Model.Cpu("minecraft:overworld@5,2,3", null, 1_024, 0, "any", null, 2, true));
        var drive = new Ae2Model.Drive("minecraft:overworld@4,64,0", "ae2:drive", List.of(), 7);
        Grid g = grid(OWNER, 10);
        var full = new Grid(g.dimension(), g.x(), g.y(), g.z(), g.owner(), g.ownerName(), g.nodes(), g.controllers(),
                g.state(), false, g.channels(), g.energy(), g.storage(),
                new Ae2Model.Crafting(2, 1, 2_048, 0, cpus), Ae2Model.Kind.CONTROLLER, null, List.of(drive));
        var resources = Ae2Mapping.snapshot(AT, List.of(full)).resources();
        assertEquals("true", resources.get(Ae2Mapping.cpu("minecraft:overworld@1,2,3")).get("suspended"));
        assertEquals("false", resources.get(Ae2Mapping.cpu("minecraft:overworld@5,2,3")).get("suspended"),
                "an idle CPU is never suspended");
        assertEquals("7", resources.get(Ae2Mapping.drive("minecraft:overworld@4,64,0")).get("priority"));
    }

    @Test
    void providersAreResourcesAndTheirPatternsTheGridsTable() {
        var provider = new Ae2Model.Provider("minecraft:overworld@4,64,0/north", "ae2:cable_pattern_provider", null,
                "Electric Furnace", "item:gtceu:lv_electric_furnace", 2, true, "none", "lock_until_result", true, 1,
                "minecraft:furnace");
        var stack = new io.github.jopojp.overseer.core.provider.Stack("item:minecraft:iron_ingot", "item", "Iron Ingot", 1);
        var input = new io.github.jopojp.overseer.core.provider.Stack("item:minecraft:raw_iron", "item", "Raw \"Iron\"", 2);
        var pattern = new Ae2Model.Pattern(provider.id(), stack, List.of(input));
        Grid g = grid(OWNER, 10);
        var full = new Grid(g.dimension(), g.x(), g.y(), g.z(), g.owner(), g.ownerName(), g.nodes(), g.controllers(),
                g.state(), false, g.channels(), g.energy(), g.storage(), g.crafting(), Ae2Model.Kind.CONTROLLER, null,
                List.of(), java.util.Map.of(), java.util.Set.of(), List.of(provider), List.of(pattern), null, List.of());
        var snapshot = Ae2Mapping.snapshot(AT, List.of(full));
        var ref = Ae2Mapping.provider("minecraft:overworld@4,64,0/north");
        var attrs = snapshot.resources().get(ref);
        assertEquals("", attrs.get("name"));
        assertEquals("Electric Furnace", attrs.get("group"));
        assertEquals("lock_until_result", attrs.get("locked"));
        assertEquals("minecraft:furnace", attrs.get("icon"));
        assertEquals("2", attrs.get("priority"));
        var rows = snapshot.details().get(Ae2Mapping.grid(g.id()));
        assertEquals(1, rows.size());
        assertEquals("item:minecraft:iron_ingot", rows.get(0).get("output"));
        var inputs = com.google.gson.JsonParser.parseString(rows.get(0).get("inputs")).getAsJsonArray();
        assertEquals("Raw \"Iron\"", inputs.get(0).getAsJsonObject().get("name").getAsString(), "names are escaped JSON");
        assertEquals(2, inputs.get(0).getAsJsonObject().get("amount").getAsLong());
        assertEquals(Ae2Mapping.grid(g.id()), Ae2Mapping.AccessIndex.of(List.of(full)).parent(ref));
        var noPatterns = Ae2Mapping.snapshot(AT, List.of(full.withPatterns(null)));
        assertFalse(noPatterns.details().containsKey(Ae2Mapping.grid(g.id())), "no table without patterns");
    }

    @Test
    void devicesWithoutChannelAreCountedAndListed() {
        Grid g = grid(OWNER, 10).withOffline(List.of(
                new Ae2Model.Offline("minecraft:overworld@1,2,3/north", "item:ae2:import_bus", "ME Import Bus"),
                new Ae2Model.Offline("minecraft:overworld@4,5,6", "item:ae2:drive", "ME Drive")));
        var snapshot = Ae2Mapping.snapshot(AT, List.of(g));
        var ref = Ae2Mapping.grid(g.id());
        assertTrue(snapshot.samples().stream().anyMatch(s -> s.resource().equals(ref) && s.metric().equals("no_channel")
                && s.value() == 2));
        var rows = snapshot.details().get(ref);
        assertEquals(2, rows.size());
        assertEquals("offline", rows.get(0).get("row"));
        assertEquals("ME Import Bus", rows.get(0).get("name"));
        assertFalse(Ae2Mapping.snapshot(AT, List.of(grid(OWNER, 10))).samples().stream()
                .anyMatch(s -> s.metric().equals("no_channel")), "not checked yet: no value");
    }

    @Test
    void jobsWithoutProgressForFiveMinutesAreStalled() {
        var tracker = new Ae2Mapping.StallTracker();
        java.util.function.LongFunction<List<Grid>> withProgress = progress -> {
            var job = new Ae2Model.Job("item:minecraft:stick", "item", "Stick", 64, 100, progress, 1);
            var cpus = List.of(new Ae2Model.Cpu("minecraft:overworld@1,2,3", null, 1_024, 0, "any", job, 1, false));
            Grid g = grid(OWNER, 10);
            return List.of(new Grid(g.dimension(), g.x(), g.y(), g.z(), g.owner(), g.ownerName(), g.nodes(),
                    g.controllers(), g.state(), false, g.channels(), g.energy(), g.storage(),
                    new Ae2Model.Crafting(1, 1, 1_024, 0, cpus), Ae2Model.Kind.CONTROLLER));
        };
        java.util.function.LongFunction<io.github.jopojp.overseer.core.time.Timestamp> at = minute ->
                new io.github.jopojp.overseer.core.time.Timestamp(java.time.Instant.EPOCH.plusSeconds(minute * 60),
                        minute * 60_000, 1);
        assertTrue(tracker.update(withProgress.apply(10), at.apply(0)).isEmpty());
        assertTrue(tracker.update(withProgress.apply(10), at.apply(4)).isEmpty(), "four minutes is patience");
        var stalled = tracker.update(withProgress.apply(10), at.apply(5));
        assertEquals(java.time.Instant.EPOCH, stalled.get("minecraft:overworld@1,2,3"), "since the last progress");
        assertTrue(tracker.update(withProgress.apply(11), at.apply(6)).isEmpty(), "progress clears it");
        var attrs = Ae2Mapping.snapshot(AT, withProgress.apply(10), java.util.Map.of("minecraft:overworld@1,2,3",
                java.time.Instant.EPOCH)).resources().get(Ae2Mapping.cpu("minecraft:overworld@1,2,3"));
        assertEquals("1970-01-01T00:00:00Z", attrs.get("stalledSince"));
    }

    @Test
    void busesAreResourcesWithTheirSettings() {
        var iron = new io.github.jopojp.overseer.core.provider.Stack("item:minecraft:iron_ingot", "item", "Iron \"Ingot\"", 0);
        var bus = new Ae2Model.Bus("minecraft:overworld@1,2,3/east", "ae2:export_bus", "export", null, "LV Macerator",
                "gtceu:lv_macerator", List.of(iron), java.util.Map.of("ae2:speed_card", 2, "ae2:crafting_card", 1),
                "high_signal", true, "roundrobin", 1, true);
        Grid g = grid(OWNER, 10).withBuses(List.of(bus));
        var ref = Ae2Mapping.bus("minecraft:overworld@1,2,3/east");
        var attrs = Ae2Mapping.snapshot(AT, List.of(g)).resources().get(ref);
        assertEquals("export", attrs.get("kind"));
        assertEquals("LV Macerator", attrs.get("target"));
        assertEquals("ae2:crafting_card=1;ae2:speed_card=2", attrs.get("upgrades"), "sorted, stable");
        assertEquals("true", attrs.get("craftOnly"));
        assertEquals("1", attrs.get("requested"));
        var filter = com.google.gson.JsonParser.parseString(attrs.get("filter")).getAsJsonArray();
        assertEquals("Iron \"Ingot\"", filter.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(Ae2Mapping.grid(g.id()), Ae2Mapping.AccessIndex.of(List.of(g)).parent(ref));
    }

    @Test
    void flowsGoToTheirGridButNeverToHiddenOnes() {
        var ore = new io.github.jopojp.overseer.core.provider.Stack("item:minecraft:raw_iron", "item", "Raw Iron", 0);
        var flow = new io.github.jopojp.overseer.core.provider.Flow("minecraft:overworld@1,2,3/east", "import_bus",
                "ME Import Bus", ore, 16, 0);
        Grid g = grid(OWNER, 10);
        Grid hidden = grid(null, 20);
        hidden = new Grid(hidden.dimension(), hidden.x(), hidden.y(), hidden.z(), null, null, hidden.nodes(), 0,
                hidden.state(), false, hidden.channels(), hidden.energy(), hidden.storage(), hidden.crafting(),
                Ae2Model.Kind.HIDDEN);
        var snapshot = Ae2Mapping.snapshot(AT, List.of(g, hidden), java.util.Map.of(),
                java.util.Map.of(g.id(), List.of(flow), hidden.id(), List.of(flow), "minecraft:overworld@99,0,0", List.of(flow)));
        assertEquals(java.util.Set.of(Ae2Mapping.grid(g.id())), snapshot.flows().keySet());
        assertEquals(16, snapshot.flows().get(Ae2Mapping.grid(g.id())).get(0).inserted());
    }

    @Test
    void restockStatesAreRowsOfTheGridSortedByKey() {
        Grid g = grid(OWNER, 10);
        var since = java.time.Instant.parse("2026-01-02T03:04:05Z");
        var snapshot = Ae2Mapping.snapshot(AT, List.of(g), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(g.id(), List.of(
                new Ae2Model.RestockState("item:minecraft:iron_ingot", "missing", since),
                new Ae2Model.RestockState("item:minecraft:diamond", "crafting", since))));
        var rows = snapshot.details().get(Ae2Mapping.grid(g.id())).stream().filter(r -> "restock".equals(r.get("row"))).toList();
        assertEquals(2, rows.size());
        assertEquals("item:minecraft:diamond", rows.get(0).get("key"));
        assertEquals("crafting", rows.get(0).get("state"));
        assertEquals("2026-01-02T03:04:05Z", rows.get(1).get("since"));
    }
}
