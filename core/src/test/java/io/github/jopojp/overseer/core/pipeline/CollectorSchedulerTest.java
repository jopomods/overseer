package io.github.jopojp.overseer.core.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jopojp.overseer.core.provider.DataProvider;
import io.github.jopojp.overseer.core.testing.FakeProvider;
import io.github.jopojp.overseer.core.testing.FakeTime;
import io.github.jopojp.overseer.core.time.UptimeClock;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CollectorSchedulerTest {
    private static final long BUDGET = TimeUnit.MILLISECONDS.toNanos(1);

    private final FakeTime time = new FakeTime();
    private final UptimeClock clock = new UptimeClock(0, 1, time, time.wallClock());
    private final SnapshotQueue queue = new SnapshotQueue(1000);
    private final LatestState latest = new LatestState();

    private CollectorScheduler scheduler(DataProvider... providers) {
        return new CollectorScheduler(List.of(providers), clock, queue, latest, time);
    }

    private void ticks(CollectorScheduler scheduler, int count) {
        for (int i = 0; i < count; i++) {
            scheduler.tick();
            time.advance(50, TimeUnit.MILLISECONDS);
        }
    }

    @Test
    void runsEachProviderAtItsInterval() {
        var everySecond = new FakeProvider("a", 20, BUDGET, time);
        var everyTick = new FakeProvider("b", 1, BUDGET, time);
        var scheduler = scheduler(everySecond, everyTick);

        ticks(scheduler, 100);

        assertEquals(5, everySecond.calls().size());
        assertEquals(100, everyTick.calls().size());
        assertEquals(105, queue.size());
        assertTrue(latest.get("a").isPresent());
    }

    @Test
    void failingProviderDoesNotAffectOthers() {
        var broken = new FakeProvider("broken", 1, BUDGET, time).failWith(new IllegalStateException("boom"));
        var healthy = new FakeProvider("healthy", 1, BUDGET, time);
        var scheduler = scheduler(broken, healthy);

        ticks(scheduler, 10);

        assertEquals(10, healthy.calls().size());
        var status = statusOf(scheduler, "broken");
        assertTrue(status.errors() > 0);
        assertNotNull(status.lastError());
        assertFalse(latest.get("broken").isPresent());
    }

    @Test
    void throttlesOverBudgetProviderAndRecovers() {
        var slow = new FakeProvider("slow", 1, BUDGET, time).work(5, TimeUnit.MILLISECONDS);
        var scheduler = scheduler(slow);

        ticks(scheduler, 3);
        assertEquals(2, statusOf(scheduler, "slow").currentIntervalTicks());

        ticks(scheduler, 200);
        var throttled = statusOf(scheduler, "slow");
        assertEquals(16, throttled.currentIntervalTicks(), "capped at 16x base interval");
        assertTrue(throttled.throttled());

        slow.work(0, TimeUnit.NANOSECONDS);
        ticks(scheduler, 2_000);
        assertEquals(1, statusOf(scheduler, "slow").currentIntervalTicks());
    }

    @Test
    void allProvidersInOneTickShareTheTimestamp() {
        var a = new FakeProvider("a", 1, BUDGET, time).work(1, TimeUnit.MILLISECONDS);
        var b = new FakeProvider("b", 1, BUDGET, time);
        var scheduler = scheduler(a, b);

        ticks(scheduler, 1);

        assertEquals(a.calls().get(0), b.calls().get(0));
    }

    private static CollectorStatus statusOf(CollectorScheduler scheduler, String provider) {
        return scheduler.status().stream().filter(s -> s.provider().equals(provider)).findFirst().orElseThrow();
    }
}
