package io.github.jopojp.overseer.core.storage;

import java.util.concurrent.TimeUnit;

/**
 * Tuning of the storage writer and retention (ADR-0005, ADR-0006).
 *
 * @param batchSize             max snapshots written per transaction
 * @param heartbeatMillis       store a sample at least this often per series, even if unchanged
 * @param sessionFlushMillis    how often the current uptime is persisted while idle
 * @param rollupIntervalMillis  how often rollups and retention run
 * @param rawRetentionMillis    keep raw samples this long (server uptime); 0 = forever
 * @param minuteRetentionMillis keep minute rollups this long; 0 = forever
 * @param hourRetentionMillis   keep hour rollups this long; 0 = forever
 */
public record StorageSettings(
        int batchSize,
        long heartbeatMillis,
        long sessionFlushMillis,
        long rollupIntervalMillis,
        long rawRetentionMillis,
        long minuteRetentionMillis,
        long hourRetentionMillis) {
    public static final StorageSettings DEFAULTS = new StorageSettings(256, 60_000, 5_000, 60_000,
            TimeUnit.HOURS.toMillis(48), TimeUnit.DAYS.toMillis(14), TimeUnit.DAYS.toMillis(365));

    public StorageSettings {
        if (batchSize < 1) throw new IllegalArgumentException("batchSize must be >= 1");
        if (heartbeatMillis < 1) throw new IllegalArgumentException("heartbeatMillis must be >= 1");
        if (sessionFlushMillis < 1) throw new IllegalArgumentException("sessionFlushMillis must be >= 1");
        if (rollupIntervalMillis < 1) throw new IllegalArgumentException("rollupIntervalMillis must be >= 1");
        if (rawRetentionMillis < 0 || minuteRetentionMillis < 0 || hourRetentionMillis < 0) {
            throw new IllegalArgumentException("retention must be >= 0 (0 = forever)");
        }
    }
}
