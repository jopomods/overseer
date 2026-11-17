package io.github.jopojp.overseer.core.storage;

/**
 * Tuning of the storage writer.
 *
 * @param batchSize          max snapshots written per transaction
 * @param heartbeatMillis    store a sample at least this often per series, even if unchanged
 * @param sessionFlushMillis how often the current uptime is persisted while idle
 */
public record StorageSettings(int batchSize, long heartbeatMillis, long sessionFlushMillis) {
    public static final StorageSettings DEFAULTS = new StorageSettings(256, 60_000, 5_000);

    public StorageSettings {
        if (batchSize < 1) throw new IllegalArgumentException("batchSize must be >= 1");
        if (heartbeatMillis < 1) throw new IllegalArgumentException("heartbeatMillis must be >= 1");
        if (sessionFlushMillis < 1) throw new IllegalArgumentException("sessionFlushMillis must be >= 1");
    }
}
