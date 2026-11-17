package io.github.jopojp.overseer.core.provider;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Static self-description of a provider.
 *
 * @param id            stable provider id, e.g. {@code flux}, {@code ae2}, {@code server}
 * @param displayName   human-readable name
 * @param capabilities  what this provider delivers
 * @param metrics       all metrics this provider may emit
 * @param intervalTicks default collection interval in server ticks (20 = 1 s)
 * @param budgetNanos   soft per-collection time budget; overruns are measured and throttle the provider
 */
public record ProviderDescriptor(
        String id,
        String displayName,
        Set<Capability> capabilities,
        List<MetricDescriptor> metrics,
        int intervalTicks,
        long budgetNanos) {
    public ProviderDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        capabilities = Set.copyOf(capabilities);
        metrics = List.copyOf(metrics);
        if (intervalTicks < 1) throw new IllegalArgumentException("intervalTicks must be >= 1");
        if (budgetNanos < 1) throw new IllegalArgumentException("budgetNanos must be >= 1");
    }
}
