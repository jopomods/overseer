package io.github.jopojp.overseer.core.provider;

/** What a provider can deliver. Drives which API routes and UI sections exist. */
public enum Capability {
    /** Numeric samples (gauges and rates) that can be charted over time. */
    METRICS,
    /** Discrete state changes: added, removed, renamed, membership changed. */
    EVENTS,
    /** Item/fluid inventories (e.g. AE2 storage). */
    INVENTORY,
    /** Has per-resource access control that must be enforced (ADR-0008). */
    ACCESS_CONTROL,
    /**
     * Owners (players with edit rights) may share resources with single players in Overseer
     * (ADR-0011). For mods without their own member lists, e.g. AE2.
     */
    SHARING
}
