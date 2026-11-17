package io.github.jopojp.overseer.core.provider;

/** How a metric behaves over time; decides storage and rollup semantics (ADR-0005). */
public enum MetricKind {
    /** A level at a point in time, e.g. stored energy, TPS. */
    GAUGE,
    /** A flow per tick, e.g. FE/t input. */
    RATE
}
