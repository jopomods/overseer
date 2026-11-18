package io.github.jopojp.overseer.forge.integration.profiler;

import io.github.jopojp.overseer.core.provider.MetricDescriptor;
import io.github.jopojp.overseer.core.provider.MetricKind;
import io.github.jopojp.overseer.core.provider.ProviderSnapshot;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.core.provider.Sample;
import io.github.jopojp.overseer.core.time.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns per-object tick timings into resources, samples and the top list (ADR-0014). Pure: no
 * Minecraft types, unit-tested.
 *
 * <p>Resources: {@code profiler/total/all} (sum of everything measured) and one
 * {@code profiler/type/<kind>:<registry id>} per block or entity type among the largest
 * {@value #MAX_TYPES}; the rest is summed as {@code profiler/type/other}.
 */
public final class ProfilerMapping {
    public static final String PROVIDER = "profiler";
    public static final ResourceRef TOTAL = new ResourceRef(PROVIDER, "total", "all");
    public static final ResourceRef OTHER = new ResourceRef(PROVIDER, "type", "other");
    static final int MAX_TYPES = 15;
    static final int TOP_OBJECTS = 50;

    static final MetricDescriptor TICK_US = new MetricDescriptor("tick_us", MetricKind.GAUGE, "µs", 0.05);
    static final MetricDescriptor OBJECTS = new MetricDescriptor("objects", MetricKind.GAUGE, "", 0.0);
    static final List<MetricDescriptor> METRICS = List.of(TICK_US, OBJECTS);

    private ProfilerMapping() {}

    /**
     * One measured block entity or entity.
     *
     * @param kind  {@code block} or {@code entity}
     * @param type  registry id of the block or entity type
     * @param name  display name of the type
     * @param where {@code dimension@x,y,z}
     * @param nanos average time per tick
     */
    public record Timing(String kind, String type, String name, String where, double nanos) {
        String typeKey() {
            return kind + ":" + type;
        }
    }

    /** A finished measurement: what the snapshot repeats until the next one. */
    public record Measurement(Map<ResourceRef, Map<String, String>> resources, List<Sample> samples,
            List<Map<String, String>> top) {
        public static final Measurement EMPTY = new Measurement(Map.of(), List.of(), List.of());
    }

    public static Measurement measure(List<Timing> timings) {
        return measure(timings, ns -> ns);
    }

    /**
     * @param modName display name of a mod by its namespace (e.g. {@code gtceu} -> {@code GregTech CEu})
     */
    public static Measurement measure(List<Timing> timings, java.util.function.UnaryOperator<String> modName) {
        var byType = new HashMap<String, double[]>();
        var names = new HashMap<String, String>();
        double total = 0;
        for (Timing t : timings) {
            if (!(t.nanos() > 0)) continue;
            double[] sum = byType.computeIfAbsent(t.typeKey(), k -> new double[2]);
            sum[0] += t.nanos();
            sum[1]++;
            names.putIfAbsent(t.typeKey(), t.name());
            total += t.nanos();
        }
        var types = new ArrayList<>(byType.entrySet());
        types.sort(Comparator.comparingDouble((Map.Entry<String, double[]> e) -> -e.getValue()[0])
                .thenComparing(Map.Entry::getKey));

        var resources = new LinkedHashMap<ResourceRef, Map<String, String>>();
        var samples = new ArrayList<Sample>();
        int count = (int) timings.stream().filter(t -> t.nanos() > 0).count();
        resources.put(TOTAL, Map.of());
        // Per mod: every type counts, not only the largest (namespace of the registry id).
        var byMod = new java.util.TreeMap<String, double[]>();
        for (var e : byType.entrySet()) {
            String id = e.getKey().substring(e.getKey().indexOf(':') + 1);
            String mod = id.contains(":") ? id.substring(0, id.indexOf(':')) : "minecraft";
            double[] sum = byMod.computeIfAbsent(mod, k -> new double[2]);
            sum[0] += e.getValue()[0];
            sum[1] += e.getValue()[1];
        }
        for (var e : byMod.entrySet()) {
            var ref = new ResourceRef(PROVIDER, "mod", e.getKey());
            resources.put(ref, Map.of("name", modName.apply(e.getKey())));
            samples.add(new Sample(ref, TICK_US.key(), e.getValue()[0] / 1000));
            samples.add(new Sample(ref, OBJECTS.key(), e.getValue()[1]));
        }
        samples.add(new Sample(TOTAL, TICK_US.key(), total / 1000));
        samples.add(new Sample(TOTAL, OBJECTS.key(), count));
        double other = 0;
        int otherObjects = 0;
        for (int i = 0; i < types.size(); i++) {
            var e = types.get(i);
            if (i >= MAX_TYPES) {
                other += e.getValue()[0];
                otherObjects += (int) e.getValue()[1];
                continue;
            }
            var ref = new ResourceRef(PROVIDER, "type", e.getKey());
            String key = e.getKey();
            resources.put(ref, Map.of("kind", key.substring(0, key.indexOf(':')), "name", names.get(key)));
            samples.add(new Sample(ref, TICK_US.key(), e.getValue()[0] / 1000));
            samples.add(new Sample(ref, OBJECTS.key(), e.getValue()[1]));
        }
        if (types.size() > MAX_TYPES) {
            resources.put(OTHER, Map.of("kind", "other", "name", ""));
            samples.add(new Sample(OTHER, TICK_US.key(), other / 1000));
            samples.add(new Sample(OTHER, OBJECTS.key(), otherObjects));
        }

        var top = new ArrayList<Map<String, String>>();
        timings.stream()
                .filter(t -> t.nanos() > 0)
                .sorted(Comparator.comparingDouble((Timing t) -> -t.nanos()).thenComparing(Timing::where))
                .limit(TOP_OBJECTS)
                .forEach(t -> {
                    var row = new LinkedHashMap<String, String>();
                    row.put("kind", t.kind());
                    row.put("type", t.type());
                    row.put("name", t.name());
                    row.put("where", t.where());
                    row.put("us", String.format(Locale.ROOT, "%.2f", t.nanos() / 1000));
                    top.add(row);
                });
        return new Measurement(resources, samples, top);
    }

    /** Repeats the last measurement's resources; samples only when {@code fresh}. */
    public static ProviderSnapshot snapshot(Timestamp at, Measurement m, boolean fresh, Map<String, String> state) {
        var resources = new LinkedHashMap<>(m.resources());
        if (!resources.isEmpty() || !state.isEmpty()) {
            var totalAttrs = new LinkedHashMap<String, String>(state);
            resources.put(TOTAL, totalAttrs);
        }
        return new ProviderSnapshot(PROVIDER, at, resources, fresh ? m.samples() : List.of(), Map.of(), Map.of(),
                m.top().isEmpty() ? Map.of() : Map.of(TOTAL, m.top()));
    }
}
