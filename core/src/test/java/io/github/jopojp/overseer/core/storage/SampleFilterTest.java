package io.github.jopojp.overseer.core.storage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SampleFilterTest {
    private static final long HEARTBEAT = 60_000;
    private final SampleFilter filter = new SampleFilter();

    @Test
    void firstSampleIsAlwaysStored() {
        assertTrue(filter.accept(1, 100, 0, 0.01, HEARTBEAT));
    }

    @Test
    void smallChangesInsideDeadbandAreSkipped() {
        filter.accept(1, 1000, 0, 0.01, HEARTBEAT);
        assertFalse(filter.accept(1, 1005, 1_000, 0.01, HEARTBEAT), "0.5 % change");
        assertTrue(filter.accept(1, 1020, 2_000, 0.01, HEARTBEAT), "2 % change");
    }

    @Test
    void deadbandIsMeasuredFromLastStoredValueNotLastSeen() {
        filter.accept(1, 1000, 0, 0.01, HEARTBEAT);
        filter.accept(1, 1006, 1_000, 0.01, HEARTBEAT); // skipped
        assertTrue(filter.accept(1, 1012, 2_000, 0.01, HEARTBEAT), "drift of 1.2 % since last stored");
    }

    @Test
    void heartbeatStoresUnchangedValue() {
        filter.accept(1, 42, 0, 0.01, HEARTBEAT);
        assertFalse(filter.accept(1, 42, 59_999, 0.01, HEARTBEAT));
        assertTrue(filter.accept(1, 42, 60_000, 0.01, HEARTBEAT));
    }

    @Test
    void zeroToNonZeroIsAChange() {
        filter.accept(1, 0, 0, 0.01, HEARTBEAT);
        assertTrue(filter.accept(1, 3, 1_000, 0.01, HEARTBEAT));
    }

    @Test
    void zeroDeadbandStoresEveryChange() {
        filter.accept(1, 1000, 0, 0, HEARTBEAT);
        assertTrue(filter.accept(1, 1000.0001, 1_000, 0, HEARTBEAT));
        assertFalse(filter.accept(1, 1000.0001, 2_000, 0, HEARTBEAT));
    }

    @Test
    void seriesAreIndependent() {
        filter.accept(1, 10, 0, 0.01, HEARTBEAT);
        assertTrue(filter.accept(2, 10, 0, 0.01, HEARTBEAT));
    }
}
