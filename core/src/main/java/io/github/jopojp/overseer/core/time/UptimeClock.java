package io.github.jopojp.overseer.core.time;

import java.time.Clock;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Accumulates server uptime (ADR-0005).
 *
 * <p>{@link #onTick()} is called once per server tick. The real time between two ticks is
 * added to the uptime, unless the gap exceeds {@link #PAUSE_THRESHOLD_NANOS}: such a gap
 * means the game was paused (singleplayer menu) or frozen and does not count. Wall time
 * between ticks is used rather than tick counts, so lag does not stretch the clock.
 *
 * <p>Thread-safe: ticks come from the server thread, reads from any thread.
 */
public final class UptimeClock {
    public static final long PAUSE_THRESHOLD_NANOS = TimeUnit.SECONDS.toNanos(5);

    private final LongSupplier nanoTime;
    private final Clock wall;
    private final int session;
    private final long baseUptimeMillis;

    private long accumulatedNanos;
    private long lastTickNanos;
    private boolean ticked;

    /**
     * @param baseUptimeMillis uptime accumulated by all previous sessions
     * @param session          number of the session that starts now
     */
    public UptimeClock(long baseUptimeMillis, int session, LongSupplier nanoTime, Clock wall) {
        if (baseUptimeMillis < 0) throw new IllegalArgumentException("baseUptimeMillis must be >= 0");
        if (session < 1) throw new IllegalArgumentException("session must be >= 1");
        this.baseUptimeMillis = baseUptimeMillis;
        this.session = session;
        this.nanoTime = nanoTime;
        this.wall = wall;
    }

    public static UptimeClock system(long baseUptimeMillis, int session) {
        return new UptimeClock(baseUptimeMillis, session, System::nanoTime, Clock.systemUTC());
    }

    /** Advances the clock. Call once per server tick. */
    public synchronized void onTick() {
        long now = nanoTime.getAsLong();
        if (ticked) {
            long delta = now - lastTickNanos;
            if (delta > 0 && delta <= PAUSE_THRESHOLD_NANOS) {
                accumulatedNanos += delta;
            }
        }
        lastTickNanos = now;
        ticked = true;
    }

    public synchronized long uptimeMillis() {
        return baseUptimeMillis + TimeUnit.NANOSECONDS.toMillis(accumulatedNanos);
    }

    public synchronized Timestamp now() {
        return new Timestamp(wall.instant(), uptimeMillis(), session);
    }

    public int session() {
        return session;
    }
}
