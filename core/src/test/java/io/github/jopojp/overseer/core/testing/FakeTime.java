package io.github.jopojp.overseer.core.testing;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Manually advanced monotonic and wall clock for deterministic tests. */
public final class FakeTime implements LongSupplier {
    private long nanos;
    private Instant wall;

    public FakeTime(Instant start) {
        this.wall = start;
    }

    public FakeTime() {
        this(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Override
    public synchronized long getAsLong() {
        return nanos;
    }

    /** Advances both clocks. */
    public synchronized void advance(long amount, TimeUnit unit) {
        nanos += unit.toNanos(amount);
        wall = wall.plusNanos(unit.toNanos(amount));
    }

    /** Advances only the wall clock (e.g. the server was offline). */
    public synchronized void advanceWallOnly(long amount, TimeUnit unit) {
        wall = wall.plusNanos(unit.toNanos(amount));
    }

    public Clock wallClock() {
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                synchronized (FakeTime.this) {
                    return wall;
                }
            }
        };
    }
}
