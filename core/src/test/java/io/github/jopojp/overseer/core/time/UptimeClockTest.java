package io.github.jopojp.overseer.core.time;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.jopojp.overseer.core.testing.FakeTime;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class UptimeClockTest {
    private final FakeTime time = new FakeTime();

    private void tickEvery50ms(UptimeClock clock, int ticks) {
        for (int i = 0; i < ticks; i++) {
            clock.onTick();
            time.advance(50, TimeUnit.MILLISECONDS);
        }
    }

    @Test
    void accumulatesRealTimeBetweenTicks() {
        var clock = new UptimeClock(0, 1, time, time.wallClock());
        tickEvery50ms(clock, 21); // 21 ticks span 20 intervals
        assertEquals(1_000, clock.uptimeMillis());
    }

    @Test
    void lagDoesNotStretchUptime() {
        var clock = new UptimeClock(0, 1, time, time.wallClock());
        // 10 TPS: ticks 100 ms apart. One second of real time is still one second.
        for (int i = 0; i < 11; i++) {
            clock.onTick();
            time.advance(100, TimeUnit.MILLISECONDS);
        }
        assertEquals(1_000, clock.uptimeMillis());
    }

    @Test
    void pauseLongerThanThresholdDoesNotCount() {
        var clock = new UptimeClock(0, 1, time, time.wallClock());
        clock.onTick();
        time.advance(1, TimeUnit.SECONDS);
        clock.onTick();
        time.advance(10, TimeUnit.MINUTES); // singleplayer pause menu
        clock.onTick();
        time.advance(1, TimeUnit.SECONDS);
        clock.onTick();
        assertEquals(2_000, clock.uptimeMillis());
    }

    @Test
    void continuesFromPreviousSessions() {
        var clock = new UptimeClock(5_000, 3, time, time.wallClock());
        tickEvery50ms(clock, 3);
        var now = clock.now();
        assertEquals(5_100, now.uptimeMillis());
        assertEquals(3, now.session());
    }
}
