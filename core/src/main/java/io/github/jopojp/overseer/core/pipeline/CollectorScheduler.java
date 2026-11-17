package io.github.jopojp.overseer.core.pipeline;

import io.github.jopojp.overseer.core.provider.DataProvider;
import io.github.jopojp.overseer.core.provider.ProviderDescriptor;
import io.github.jopojp.overseer.core.provider.ProviderSnapshot;
import io.github.jopojp.overseer.core.time.Timestamp;
import io.github.jopojp.overseer.core.time.UptimeClock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs due providers on the server thread and hands their snapshots off (ADR-0004).
 *
 * <p>The platform calls {@link #tick()} once per server tick. Each provider runs every
 * {@code intervalTicks}. Run time is measured against the provider's budget:
 * <ul>
 *   <li>{@value #OVERRUNS_TO_THROTTLE} consecutive overruns or errors double the interval,
 *       up to {@value #MAX_THROTTLE_FACTOR} times the base interval;</li>
 *   <li>{@value #CLEAN_RUNS_TO_RECOVER} consecutive runs within budget halve it again.</li>
 * </ul>
 * A throwing provider never affects other providers or the tick.
 *
 * <p>Not thread-safe except {@link #status()}: call everything else from the server thread.
 */
public final class CollectorScheduler {
    static final int OVERRUNS_TO_THROTTLE = 3;
    static final int CLEAN_RUNS_TO_RECOVER = 20;
    static final int MAX_THROTTLE_FACTOR = 16;
    private static final double EWMA_ALPHA = 0.2;

    private static final Logger LOG = LoggerFactory.getLogger(CollectorScheduler.class);

    private final List<Slot> slots;
    private final UptimeClock clock;
    private final SnapshotQueue queue;
    private final LatestState latest;
    private final LongSupplier nanoTime;
    private long tick;

    public CollectorScheduler(
            List<DataProvider> providers,
            UptimeClock clock,
            SnapshotQueue queue,
            LatestState latest,
            LongSupplier nanoTime) {
        this.clock = Objects.requireNonNull(clock);
        this.queue = Objects.requireNonNull(queue);
        this.latest = Objects.requireNonNull(latest);
        this.nanoTime = Objects.requireNonNull(nanoTime);
        var list = new ArrayList<Slot>();
        for (DataProvider provider : providers) list.add(new Slot(provider));
        this.slots = List.copyOf(list);
    }

    /** Advances the uptime clock and runs every provider that is due. Server thread only. */
    public void tick() {
        tick++;
        clock.onTick();
        Timestamp now = null;
        for (Slot slot : slots) {
            if (tick < slot.nextDueTick) continue;
            if (now == null) now = clock.now();
            slot.run(now);
        }
    }

    /** Current health of every provider. Safe from any thread. */
    public List<CollectorStatus> status() {
        var result = new ArrayList<CollectorStatus>(slots.size());
        for (Slot slot : slots) result.add(slot.status);
        return List.copyOf(result);
    }

    /** Per-provider scheduling state. Mutated on the server thread only. */
    private final class Slot {
        final DataProvider provider;
        final ProviderDescriptor descriptor;
        int intervalTicks;
        long nextDueTick;
        int badStreak;
        int cleanStreak;
        long runs;
        long errors;
        long overruns;
        long lastNanos;
        long measured;
        double avgNanos;
        long maxNanos;
        String lastError;
        volatile CollectorStatus status;

        Slot(DataProvider provider) {
            this.provider = provider;
            this.descriptor = provider.descriptor();
            this.intervalTicks = descriptor.intervalTicks();
            this.nextDueTick = 1;
            publish();
        }

        void run(Timestamp now) {
            long start = nanoTime.getAsLong();
            ProviderSnapshot snapshot = null;
            try {
                snapshot = provider.collect(now);
                if (snapshot == null) throw new IllegalStateException("collect() returned null");
                if (!snapshot.provider().equals(descriptor.id())) {
                    throw new IllegalStateException("snapshot provider id '" + snapshot.provider()
                            + "' does not match descriptor id '" + descriptor.id() + "'");
                }
            } catch (RuntimeException e) {
                errors++;
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                LOG.warn("Overseer provider '{}' failed to collect", descriptor.id(), e);
                snapshot = null;
            }
            long elapsed = nanoTime.getAsLong() - start;

            lastNanos = elapsed;
            maxNanos = Math.max(maxNanos, elapsed);
            avgNanos = ++measured == 1 ? elapsed : EWMA_ALPHA * elapsed + (1 - EWMA_ALPHA) * avgNanos;

            boolean overrun = elapsed > descriptor.budgetNanos();
            if (overrun) overruns++;

            if (snapshot != null) {
                runs++;
                queue.offer(snapshot);
                latest.put(snapshot);
            }

            adapt(snapshot == null || overrun);
            nextDueTick = tick + intervalTicks;
            publish();
        }

        private void adapt(boolean bad) {
            int base = descriptor.intervalTicks();
            if (bad) {
                cleanStreak = 0;
                if (++badStreak >= OVERRUNS_TO_THROTTLE && intervalTicks < base * MAX_THROTTLE_FACTOR) {
                    intervalTicks = Math.min(intervalTicks * 2, base * MAX_THROTTLE_FACTOR);
                    badStreak = 0;
                    LOG.warn("Overseer provider '{}' is over budget; collecting every {} ticks now",
                            descriptor.id(), intervalTicks);
                }
            } else {
                badStreak = 0;
                if (++cleanStreak >= CLEAN_RUNS_TO_RECOVER && intervalTicks > base) {
                    intervalTicks = Math.max(intervalTicks / 2, base);
                    cleanStreak = 0;
                }
            }
        }

        private void publish() {
            status = new CollectorStatus(descriptor.id(), descriptor.intervalTicks(), intervalTicks,
                    descriptor.budgetNanos(), runs, errors, overruns, lastNanos, Math.round(avgNanos),
                    maxNanos, lastError);
        }
    }
}
