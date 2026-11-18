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
        assertEquals(Ae2Mapping.METRICS.size(), snapshot.samples().size());
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
}
