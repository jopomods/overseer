package io.github.jopojp.overseer.forge.integration.profiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.core.time.Timestamp;
import io.github.jopojp.overseer.forge.integration.profiler.ProfilerMapping.Timing;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProfilerMappingTest {
    private static final Timestamp AT = new Timestamp(Instant.EPOCH, 0, 1);

    private static double value(ProfilerMapping.Measurement m, ResourceRef ref, String metric) {
        return m.samples().stream().filter(s -> s.resource().equals(ref) && s.metric().equals(metric))
                .mapToDouble(s -> s.value()).findFirst().orElse(-1);
    }

    @Test
    void sumsPerTypeAndListsTheMostExpensiveObjects() {
        var m = ProfilerMapping.measure(List.of(
                new Timing("block", "gtceu:ev_macerator", "EV Macerator", "minecraft:overworld@1,64,1", 40_000),
                new Timing("block", "gtceu:ev_macerator", "EV Macerator", "minecraft:overworld@2,64,1", 20_000),
                new Timing("entity", "minecraft:zombie", "Zombie", "minecraft:overworld@9,64,9", 5_000),
                new Timing("block", "minecraft:hopper", "Hopper", "minecraft:overworld@3,64,1", 0)));
        var macerator = new ResourceRef("profiler", "type", "block:gtceu:ev_macerator");
        assertEquals(60, value(m, macerator, "tick_us"), 1e-9);
        assertEquals(2, value(m, macerator, "objects"));
        assertEquals(65, value(m, ProfilerMapping.TOTAL, "tick_us"), 1e-9);
        assertEquals(3, value(m, ProfilerMapping.TOTAL, "objects"), "objects without time are skipped");
        assertEquals("EV Macerator", m.resources().get(macerator).get("name"));
        assertEquals("minecraft:overworld@1,64,1", m.top().get(0).get("where"));
        assertEquals("40.00", m.top().get(0).get("us"));
    }

    @Test
    void everyTypeCountsTowardsItsMod() {
        var timings = new ArrayList<Timing>();
        for (int i = 0; i < 20; i++) timings.add(new Timing("block", "gtceu:m" + i, "M" + i, "d@" + i + ",0,0", 1000));
        timings.add(new Timing("entity", "minecraft:zombie", "Zombie", "d@0,0,9", 500));
        var m = ProfilerMapping.measure(timings, ns -> ns.equals("gtceu") ? "GregTech CEu" : ns);
        var gtceu = new ResourceRef("profiler", "mod", "gtceu");
        assertEquals(20, value(m, gtceu, "tick_us"), 1e-9, "all 20 types, also those summed as other");
        assertEquals("GregTech CEu", m.resources().get(gtceu).get("name"));
        assertEquals(0.5, value(m, new ResourceRef("profiler", "mod", "minecraft"), "tick_us"), 1e-9);
    }

    @Test
    void smallTypesAreSummedAsOther() {
        var timings = new ArrayList<Timing>();
        for (int i = 0; i < 20; i++) timings.add(new Timing("block", "mod:b" + i, "B" + i, "d@" + i + ",0,0", 1000 * (i + 1)));
        var m = ProfilerMapping.measure(timings);
        long types = m.resources().keySet().stream().filter(r -> r.type().equals("type")).count();
        assertEquals(ProfilerMapping.MAX_TYPES + 1, types, "15 types and other");
        assertEquals(15, value(m, ProfilerMapping.OTHER, "tick_us"), 1e-9, "1+2+3+4+5 µs");
    }

    @Test
    void samplesOnlyWhenFreshButResourcesStay() {
        var m = ProfilerMapping.measure(List.of(new Timing("block", "a:b", "B", "d@0,0,0", 1000)));
        var stale = ProfilerMapping.snapshot(AT, m, false, Map.of());
        assertTrue(stale.samples().isEmpty());
        assertEquals(m.resources().keySet(), stale.resources().keySet());
        assertEquals(1, ProfilerMapping.snapshot(AT, m, true, Map.of()).details().get(ProfilerMapping.TOTAL).size());
        assertTrue(ProfilerMapping.snapshot(AT, ProfilerMapping.Measurement.EMPTY, false, Map.of()).resources().isEmpty());
    }
}
