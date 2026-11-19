package io.github.jopojp.overseer.core.action;

import java.time.Duration;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
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
 *
 * <p>An action may finish later than its turn on the server thread ({@link ActionHandler#start},
 * ADR-0019): the caller then waits for the provider to complete it, at most {@link #CLAIMED_LIMIT}.
 */
public final class ActionQueue implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ActionQueue.class);
    private static final int WAITING = 0;
    private static final int CLAIMED = 1;
    private static final int CANCELLED = 2;

    /** An action waiting for the server thread. */
    public static final class Ticket {
        private final Supplier<? extends CompletionStage<ActionResult>> work;
        private final CompletableFuture<ActionResult> result = new CompletableFuture<>();
        private final AtomicInteger state = new AtomicInteger(WAITING);

        private Ticket(Supplier<? extends CompletionStage<ActionResult>> work) {
            this.work = work;
        }

        private boolean cancel(String message) {
            if (!state.compareAndSet(WAITING, CANCELLED)) return false;
            result.complete(ActionResult.error(ActionResult.Status.BUSY, message));
            return true;
        }
    }

    /** How long a caller waits for an action the server thread started but has not finished. */
    public static final Duration CLAIMED_LIMIT = Duration.ofSeconds(60);

    private final Queue<Ticket> pending = new ConcurrentLinkedQueue<>();
    /** Started, not finished yet (actions that finish later). */
    private final Set<Ticket> running = ConcurrentHashMap.newKeySet();
    private final AtomicInteger size = new AtomicInteger();
    private final int capacity;
    private volatile boolean closed;

    public ActionQueue(int capacity) {
        this.capacity = capacity;
    }

    /** Queues an action; refused (already completed with {@code BUSY}) when full or closed. */
    public Ticket submit(Supplier<ActionResult> work) {
        return submitAsync(() -> CompletableFuture.completedFuture(work.get()));
    }

    /** Queues an action whose outcome may come later (ADR-0019). */
    public Ticket submitAsync(Supplier<? extends CompletionStage<ActionResult>> work) {
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
            CompletionStage<ActionResult> stage;
            try {
                stage = ticket.work.get();
                if (stage == null) throw new IllegalStateException("action returned no result");
            } catch (RuntimeException e) {
                stage = CompletableFuture.failedFuture(e);
            }
            Ticket started = ticket;
            var done = stage.toCompletableFuture();
            if (!done.isDone()) running.add(started);
            done.whenComplete((result, error) -> {
                running.remove(started);
                if (error == null && result != null) {
                    started.result.complete(result);
                    return;
                }
                LOG.error("Overseer world action failed", error);
                Throwable cause = error == null ? new IllegalStateException("no result") : error;
                if (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
                    cause = cause.getCause();
                }
                started.result.complete(ActionResult.error(ActionResult.Status.FAILED,
                        "The change failed on the server: " + cause.getClass().getSimpleName()));
            });
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
            return awaitClaimed(ticket);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (ticket.cancel("Interrupted. Nothing was changed.")) return ticket.result.join();
            return ticket.result.join();
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    /** An action the server thread started: wait for it, but not forever. */
    private ActionResult awaitClaimed(Ticket ticket) {
        try {
            return ticket.result.get(CLAIMED_LIMIT.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            return ActionResult.error(ActionResult.Status.FAILED, "The server did not finish in time.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ActionResult.error(ActionResult.Status.FAILED, "Interrupted while the server was working on it.");
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
        // Started but unfinished: their provider stops with the server, so answer now.
        for (Ticket started : running) {
            started.result.complete(ActionResult.error(ActionResult.Status.FAILED, "The server is shutting down."));
        }
        running.clear();
    }
}
