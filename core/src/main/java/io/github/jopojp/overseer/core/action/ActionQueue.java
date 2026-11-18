package io.github.jopojp.overseer.core.action;

import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hands world actions from HTTP threads to the server thread (ADR-0004, ADR-0016).
 *
 * <p>An action either runs completely on the server thread or not at all: a caller that gives
 * up waiting cancels it, and it is only cancelled if the server thread has not claimed it yet.
 * So a {@link ActionResult.Status#BUSY} answer always means nothing was changed.
 */
public final class ActionQueue implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ActionQueue.class);
    private static final int WAITING = 0;
    private static final int CLAIMED = 1;
    private static final int CANCELLED = 2;

    /** An action waiting for the server thread. */
    public static final class Ticket {
        private final Supplier<ActionResult> work;
        private final CompletableFuture<ActionResult> result = new CompletableFuture<>();
        private final AtomicInteger state = new AtomicInteger(WAITING);

        private Ticket(Supplier<ActionResult> work) {
            this.work = work;
        }

        private boolean cancel(String message) {
            if (!state.compareAndSet(WAITING, CANCELLED)) return false;
            result.complete(ActionResult.error(ActionResult.Status.BUSY, message));
            return true;
        }
    }

    private final Queue<Ticket> pending = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger();
    private final int capacity;
    private volatile boolean closed;

    public ActionQueue(int capacity) {
        this.capacity = capacity;
    }

    /** Queues an action; refused (already completed with {@code BUSY}) when full or closed. */
    public Ticket submit(Supplier<ActionResult> work) {
        var ticket = new Ticket(work);
        if (closed) {
            ticket.cancel("The server is shutting down.");
        } else if (size.incrementAndGet() > capacity) {
            size.decrementAndGet();
            ticket.cancel("Too many changes at once. Try again in a moment.");
        } else {
            pending.add(ticket);
            if (closed) ticket.cancel("The server is shutting down.");
        }
        return ticket;
    }

    /**
     * Runs queued actions. Server thread only. Runs at least one waiting action, then more
     * until {@code budgetNanos} is used up, so a burst cannot stall a tick.
     */
    public void drain(long budgetNanos, LongSupplier nanoTime) {
        long start = nanoTime.getAsLong();
        boolean first = true;
        Ticket ticket;
        while ((first || nanoTime.getAsLong() - start < budgetNanos) && (ticket = pending.poll()) != null) {
            size.decrementAndGet();
            if (!ticket.state.compareAndSet(WAITING, CLAIMED)) continue; // the caller gave up
            first = false;
            ActionResult result;
            try {
                result = ticket.work.get();
                if (result == null) throw new IllegalStateException("action returned no result");
            } catch (RuntimeException e) {
                LOG.error("Overseer world action failed", e);
                result = ActionResult.error(ActionResult.Status.FAILED,
                        "The change failed on the server: " + e.getClass().getSimpleName());
            }
            ticket.result.complete(result);
        }
    }

    /**
     * Waits for the result. After {@code timeout} an action the server thread has not started
     * yet is cancelled; one it has started is waited for until it finishes.
     */
    public ActionResult await(Ticket ticket, Duration timeout) {
        try {
            return ticket.result.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            ticket.cancel("The server did not get to the change in time. Nothing was changed.");
            return ticket.result.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (ticket.cancel("Interrupted. Nothing was changed.")) return ticket.result.join();
            return ticket.result.join();
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    /** Actions waiting right now. */
    public int size() {
        return Math.max(0, size.get());
    }

    /** Cancels everything still waiting; later submissions are refused. */
    @Override
    public void close() {
        closed = true;
        Ticket ticket;
        while ((ticket = pending.poll()) != null) {
            size.decrementAndGet();
            ticket.cancel("The server is shutting down.");
        }
    }
}
