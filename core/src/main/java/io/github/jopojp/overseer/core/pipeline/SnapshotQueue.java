package io.github.jopojp.overseer.core.pipeline;

import io.github.jopojp.overseer.core.provider.ProviderSnapshot;
import java.util.Collection;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded hand-off from the server thread to the storage writer (ADR-0004).
 *
 * <p>{@link #offer} never blocks: when the queue is full the oldest snapshot is dropped
 * and counted. Under overload Overseer loses data points, never ticks.
 */
public final class SnapshotQueue {
    private final LinkedBlockingDeque<ProviderSnapshot> deque;
    private final AtomicLong dropped = new AtomicLong();

    public SnapshotQueue(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be >= 1");
        this.deque = new LinkedBlockingDeque<>(capacity);
    }

    /** Enqueues without blocking; drops the oldest entry if full. */
    public void offer(ProviderSnapshot snapshot) {
        while (!deque.offerLast(snapshot)) {
            if (deque.pollFirst() != null) {
                dropped.incrementAndGet();
            }
        }
    }

    /** Waits up to the timeout for the next snapshot; {@code null} if none arrived. */
    public ProviderSnapshot poll(long timeout, TimeUnit unit) throws InterruptedException {
        return deque.pollFirst(timeout, unit);
    }

    /** Moves up to {@code max} queued snapshots into {@code target} without waiting. */
    public int drainTo(Collection<? super ProviderSnapshot> target, int max) {
        return deque.drainTo(target, max);
    }

    /** Total snapshots dropped because the queue was full. */
    public long dropped() {
        return dropped.get();
    }

    public int size() {
        return deque.size();
    }
}
