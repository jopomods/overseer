package io.github.jopojp.overseer.core.time;

import java.time.Instant;
import java.util.Objects;

/**
 * A point in time as Overseer records it (ADR-0005).
 *
 * <p>Every stored value carries both clocks:
 * <ul>
 *   <li>{@code wall}: calendar time (UTC). Drives the "calendar time" axis.</li>
 *   <li>{@code uptimeMillis}: accumulated real milliseconds during which the server was
 *       actually ticking, across all sessions. Monotonic. Drives the "server uptime" axis,
 *       where offline periods and singleplayer pauses collapse to a marker.</li>
 *   <li>{@code session}: uptime session number; increments on every server start.</li>
 * </ul>
 */
public record Timestamp(Instant wall, long uptimeMillis, int session) implements Comparable<Timestamp> {
    public Timestamp {
        Objects.requireNonNull(wall, "wall");
        if (uptimeMillis < 0) throw new IllegalArgumentException("uptimeMillis must be >= 0");
        if (session < 1) throw new IllegalArgumentException("session must be >= 1");
    }

    /** Orders by uptime, the monotonic clock. Wall time may jump (NTP, manual changes). */
    @Override
    public int compareTo(Timestamp other) {
        return Long.compare(uptimeMillis, other.uptimeMillis);
    }
}
