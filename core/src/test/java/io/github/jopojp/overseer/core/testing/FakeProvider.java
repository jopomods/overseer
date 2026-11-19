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

    private ProviderDescriptor descriptor;
    private final FakeTime time;
    private final Map<ResourceRef, Map<String, String>> resources = new LinkedHashMap<>();
    private final Map<ResourceRef, Double> power = new LinkedHashMap<>();
    private long workNanos;
    private final Map<ResourceRef, List<io.github.jopojp.overseer.core.provider.Stack>> inventories = new LinkedHashMap<>();
    private RuntimeException failure;
    private final List<Timestamp> calls = new ArrayList<>();
    private AccessPolicy policy = AccessPolicy.ALLOW_AUTHENTICATED;

    public FakeProvider(String id, int intervalTicks, long budgetNanos, FakeTime time) {
        this.descriptor = new ProviderDescriptor(id, id, Set.of(Capability.METRICS, Capability.EVENTS),
                List.of(POWER), intervalTicks, budgetNanos);
        this.time = time;
    }

    /** Adds a capability, e.g. {@link Capability#SHARING}. */
    public FakeProvider capability(Capability capability) {
        var capabilities = new java.util.HashSet<>(descriptor.capabilities());
        capabilities.add(capability);
        descriptor = new ProviderDescriptor(descriptor.id(), descriptor.displayName(), capabilities,
                descriptor.metrics(), descriptor.intervalTicks(), descriptor.budgetNanos());
        return this;
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
    private final Map<ResourceRef, List<io.github.jopojp.overseer.core.provider.JobLine>> jobs = new LinkedHashMap<>();

    /** A running job reported with every collection from now on; empty lines = idle. */
    public FakeProvider job(ResourceRef ref, List<io.github.jopojp.overseer.core.provider.JobLine> lines) {
        if (lines.isEmpty()) jobs.remove(ref); else jobs.put(ref, List.copyOf(lines));
        return this;
    }

    private final Map<ResourceRef, List<Map<String, String>>> details = new LinkedHashMap<>();

    /** A live table reported with every collection from now on. */
    private final Map<ResourceRef, List<io.github.jopojp.overseer.core.provider.Flow>> flows = new java.util.HashMap<>();

    public FakeProvider details(ResourceRef ref, List<Map<String, String>> rows) {
        details.put(ref, List.copyOf(rows));
        return this;
    }

    /** Flows reported with the next collection only (they are what moved since the last one). */
    public FakeProvider flows(ResourceRef ref, List<io.github.jopojp.overseer.core.provider.Flow> list) {
        flows.put(ref, List.copyOf(list));
        return this;
    }

    /** Contents reported with every collection from now on. */
    public FakeProvider inventory(ResourceRef ref, List<io.github.jopojp.overseer.core.provider.Stack> stacks) {
        inventories.put(ref, List.copyOf(stacks));
        return this;
    }

    public FakeProvider work(long amount, TimeUnit unit) {
        this.workNanos = unit.toNanos(amount);
        return this;
    }

    public FakeProvider failWith(RuntimeException failure) {
        this.failure = failure;
        return this;
    }

    public FakeProvider policy(AccessPolicy policy) {
        this.policy = policy;
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
        var moved = Map.copyOf(flows);
        flows.clear();
        return new ProviderSnapshot(descriptor.id(), at, resources, samples, inventories, jobs, details, moved);
    }

    @Override
    public AccessPolicy access() {
        return policy;
    }

    private io.github.jopojp.overseer.core.action.ActionHandler actions =
            io.github.jopojp.overseer.core.action.ActionHandler.NONE;

    /** World actions this provider offers from now on (ADR-0016). */
    public FakeProvider actions(io.github.jopojp.overseer.core.action.ActionHandler actions) {
        this.actions = actions;
        return this;
    }

    @Override
    public io.github.jopojp.overseer.core.action.ActionHandler actions() {
        return actions;
    }
}
