package io.github.jopojp.overseer.core.storage;

import java.util.HashMap;
import java.util.Map;

/**
 * Decides which metric samples are worth storing (ADR-0005): a sample is kept if it is the
 * first of its series, if it leaves the relative deadband around the last stored value,
 * or if the heartbeat interval has passed since the last stored sample.
 *
 * <p>Not thread-safe; owned by the storage writer.
 */
final class SampleFilter {
    private record Last(double value, long uptimeMillis) {}

    private final Map<Long, Last> last = new HashMap<>();

    boolean accept(long seriesId, double value, long uptimeMillis, double relativeDeadband, long heartbeatMillis) {
        Last previous = last.get(seriesId);
        boolean keep = previous == null
                || uptimeMillis - previous.uptimeMillis() >= heartbeatMillis
                || leavesDeadband(previous.value(), value, relativeDeadband);
        if (keep) last.put(seriesId, new Last(value, uptimeMillis));
        return keep;
    }

    static boolean leavesDeadband(double previous, double value, double relativeDeadband) {
        if (Double.compare(previous, value) == 0) return false;
        if (relativeDeadband <= 0) return true;
        double scale = Math.max(Math.abs(previous), Math.abs(value));
        return Math.abs(value - previous) > relativeDeadband * scale;
    }
}
