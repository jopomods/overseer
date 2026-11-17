package io.github.jopojp.overseer.core;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jopojp.overseer.core.time.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TimestampTest {
    @Test
    void ordersByUptimeNotWallClock() {
        // Wall clock went backwards (e.g. NTP correction) while uptime advanced.
        var earlier = new Timestamp(Instant.parse("2026-01-01T12:00:00Z"), 1_000, 1);
        var later = new Timestamp(Instant.parse("2026-01-01T11:59:00Z"), 2_000, 1);
        assertTrue(earlier.compareTo(later) < 0);
    }

    @Test
    void rejectsSessionZero() {
        assertThrows(IllegalArgumentException.class, () -> new Timestamp(Instant.EPOCH, 0, 0));
    }
}
