package io.github.jopojp.overseer.core.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.jopojp.overseer.core.provider.ProviderSnapshot;
import io.github.jopojp.overseer.core.time.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SnapshotQueueTest {
    private static ProviderSnapshot snapshot(long uptime) {
        return new ProviderSnapshot("p", new Timestamp(Instant.EPOCH, uptime, 1), Map.of(), List.of());
    }

    @Test
    void dropsOldestWhenFull() {
        var queue = new SnapshotQueue(2);
        queue.offer(snapshot(1));
        queue.offer(snapshot(2));
        queue.offer(snapshot(3));

        var drained = new ArrayList<ProviderSnapshot>();
        queue.drainTo(drained, 10);

        assertEquals(List.of(2L, 3L), drained.stream().map(s -> s.at().uptimeMillis()).toList());
        assertEquals(1, queue.dropped());
    }
}
