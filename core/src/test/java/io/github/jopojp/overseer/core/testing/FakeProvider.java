package io.github.jopojp.overseer.core.testing;

import io.github.jopojp.overseer.core.provider.AccessPolicy;
import io.github.jopojp.overseer.core.provider.Capability;
import io.github.jopojp.overseer.core.provider.DataProvider;
import io.github.jopojp.overseer.core.provider.MetricDescriptor;
import io.github.jopojp.overseer.core.provider.MetricKind;
import io.github.jopojp.overseer.core.provider.ProviderDescriptor;
import io.github.jopojp.overseer.core.provider.ProviderSnapshot;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.core.provider.Sample;
import io.github.jopojp.overseer.core.time.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Scriptable provider: set resources and values, optionally simulate slow or failing runs. */
public final class FakeProvider implements DataProvider {
    public static final MetricDescriptor POWER = new MetricDescriptor("power", MetricKind.RATE, "FE/t", 0.01);

    private final ProviderDescriptor descriptor;
    private final FakeTime time;
    private final Map<ResourceRef, Map<String, String>> resources = new LinkedHashMap<>();
    private final Map<ResourceRef, Double> power = new LinkedHashMap<>();
    private long workNanos;
    private RuntimeException failure;
    private final List<Timestamp> calls = new ArrayList<>();

    public FakeProvider(String id, int intervalTicks, long budgetNanos, FakeTime time) {
        this.descriptor = new ProviderDescriptor(id, id, Set.of(Capability.METRICS, Capability.EVENTS),
                List.of(POWER), intervalTicks, budgetNanos);
        this.time = time;
    }

    public FakeProvider resource(ResourceRef ref, Map<String, String> attributes) {
        resources.put(ref, Map.copyOf(attributes));
        return this;
    }

    public FakeProvider remove(ResourceRef ref) {
        resources.remove(ref);
        power.remove(ref);
        return this;
    }

    public FakeProvider power(ResourceRef ref, double value) {
        power.put(ref, value);
        return this;
    }

    /** Each collect() advances the fake clock by this much, simulating work. */
    public FakeProvider work(long amount, TimeUnit unit) {
        this.workNanos = unit.toNanos(amount);
        return this;
    }

    public FakeProvider failWith(RuntimeException failure) {
        this.failure = failure;
        return this;
    }

    public List<Timestamp> calls() {
        return calls;
    }

    @Override
    public ProviderDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ProviderSnapshot collect(Timestamp at) {
        calls.add(at);
        if (workNanos > 0) time.advance(workNanos, TimeUnit.NANOSECONDS);
        if (failure != null) throw failure;
        var samples = new ArrayList<Sample>();
        power.forEach((ref, value) -> samples.add(new Sample(ref, POWER.key(), value)));
        return new ProviderSnapshot(descriptor.id(), at, resources, samples);
    }

    @Override
    public AccessPolicy access() {
        return AccessPolicy.ALLOW_AUTHENTICATED;
    }
}
