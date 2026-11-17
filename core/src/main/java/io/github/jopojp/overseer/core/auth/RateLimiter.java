package io.github.jopojp.overseer.core.auth;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Token bucket per key (usually the client address). Thread-safe.
 * Buckets idle for longer than a full refill are evicted lazily.
 */
public final class RateLimiter {
    private static final int MAX_KEYS = 10_000;

    private final int capacity;
    private final long refillNanosPerToken;
    private final LongSupplier nanoTime;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    private static final class Bucket {
        double tokens;
        long updatedNanos;
    }

    /** Allows {@code perMinute} requests per minute with bursts up to {@code perMinute}. */
    public RateLimiter(int perMinute, LongSupplier nanoTime) {
        if (perMinute < 1) throw new IllegalArgumentException("perMinute must be >= 1");
        this.capacity = perMinute;
        this.refillNanosPerToken = TimeUnit.MINUTES.toNanos(1) / perMinute;
        this.nanoTime = nanoTime;
    }

    public static RateLimiter perMinute(int perMinute) {
        return new RateLimiter(perMinute, System::nanoTime);
    }

    /** Takes one token for {@code key}; {@code false} if the limit is exceeded. */
    public boolean tryAcquire(String key) {
        long now = nanoTime.getAsLong();
        if (buckets.size() > MAX_KEYS) evictIdle(now);
        Bucket bucket = buckets.computeIfAbsent(key, k -> {
            var b = new Bucket();
            b.tokens = capacity;
            b.updatedNanos = now;
            return b;
        });
        synchronized (bucket) {
            double refill = (double) (now - bucket.updatedNanos) / refillNanosPerToken;
            bucket.tokens = Math.min(capacity, bucket.tokens + refill);
            bucket.updatedNanos = now;
            if (bucket.tokens < 1) return false;
            bucket.tokens -= 1;
            return true;
        }
    }

    private void evictIdle(long now) {
        long fullRefill = refillNanosPerToken * capacity;
        buckets.entrySet().removeIf(e -> now - e.getValue().updatedNanos > fullRefill);
    }
}
