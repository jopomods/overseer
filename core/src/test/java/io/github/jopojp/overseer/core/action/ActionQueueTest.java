package io.github.jopojp.overseer.core.action;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class ActionQueueTest {
    private static final ActionResult OK = ActionResult.ok(List.of());

    @Test
    void runsOnTheDrainingThreadAndHandsBackTheResult() {
        var queue = new ActionQueue(8);
        var ranOn = new Thread[1];
        var ticket = queue.submit(() -> {
            ranOn[0] = Thread.currentThread();
            return OK;
        });
        var waiter = CompletableFuture.supplyAsync(() -> queue.await(ticket, Duration.ofSeconds(5)));
        queue.drain(1_000_000, System::nanoTime);
        assertEquals(OK, waiter.join());
        assertEquals(Thread.currentThread(), ranOn[0]);
    }

    @Test
    void aTimedOutActionNeverRunsLater() {
        var queue = new ActionQueue(8);
        var runs = new AtomicInteger();
        var ticket = queue.submit(() -> {
            runs.incrementAndGet();
            return OK;
        });
        var result = queue.await(ticket, Duration.ofMillis(20));
        assertEquals(ActionResult.Status.BUSY, result.status());
        queue.drain(1_000_000, System::nanoTime);
        assertEquals(0, runs.get(), "cancelled action must not run");
        assertEquals(0, queue.size());
    }

    @Test
    void failuresBecomeFailedResults() {
        var queue = new ActionQueue(8);
        var ticket = queue.submit(() -> {
            throw new IllegalStateException("boom");
        });
        queue.drain(1_000_000, System::nanoTime);
        assertEquals(ActionResult.Status.FAILED, queue.await(ticket, Duration.ofSeconds(1)).status());
    }

    @Test
    void drainStopsAtTheBudgetButRunsAtLeastOne() {
        var queue = new ActionQueue(8);
        var fakeNanos = new AtomicLong();
        var runs = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            queue.submit(() -> {
                runs.incrementAndGet();
                fakeNanos.addAndGet(10);
                return OK;
            });
        }
        queue.drain(5, fakeNanos::get);
        assertEquals(1, runs.get());
        queue.drain(1_000, fakeNanos::get);
        assertEquals(3, runs.get());
    }

    @Test
    void refusesWhenFullOrClosed() {
        var queue = new ActionQueue(1);
        var first = queue.submit(() -> OK);
        var second = queue.submit(() -> OK);
        assertEquals(ActionResult.Status.BUSY, queue.await(second, Duration.ofMillis(1)).status());
        queue.close();
        assertEquals(ActionResult.Status.BUSY, queue.await(first, Duration.ofMillis(1)).status());
        var late = queue.submit(() -> OK);
        assertEquals(ActionResult.Status.BUSY, queue.await(late, Duration.ofMillis(1)).status());
        assertEquals(0, queue.size());
    }
}
