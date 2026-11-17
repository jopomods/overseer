package io.github.jopojp.overseer.core.provider;

import java.util.Objects;

/**
 * Describes one metric a provider emits.
 *
 * @param key              metric key, unique within the provider, e.g. {@code energy_input}
 * @param kind             gauge or rate
 * @param unit             base unit, e.g. {@code FE}, {@code FE/t}, {@code ms}; the UI converts for display
 * @param relativeDeadband store a new sample only if it differs by more than this fraction
 *                         from the last stored one (e.g. 0.01 = 1 %); a heartbeat sample is
 *                         stored regardless at a fixed interval (ADR-0005)
 */
public record MetricDescriptor(String key, MetricKind kind, String unit, double relativeDeadband) {
    public MetricDescriptor {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(unit, "unit");
        if (relativeDeadband < 0) throw new IllegalArgumentException("relativeDeadband must be >= 0");
    }
}
