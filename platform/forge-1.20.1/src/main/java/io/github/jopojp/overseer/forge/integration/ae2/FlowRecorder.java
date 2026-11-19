package io.github.jopojp.overseer.forge.integration.ae2;

import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Counts what passes AE2 network storages (ADR-0020), fed by {@code NetworkStorageMixin}. AE2's
 * insert/extract path is its hottest, so recording is a flag check, a thread check and a map
 * update; everything else happens once per collection in {@link Ae2Provider}. Server thread only:
 * calls from other threads (AE2's planning thread only simulates) are ignored.
 */
public final class FlowRecorder {
    /** Who acted when AE2 does not say: no machine, no player. */
    static final Object UNKNOWN = new Object();

    private static volatile boolean enabled;
    private static volatile Thread serverThread;

    /** Per storage, per source (machine, player or {@link #UNKNOWN}), per key: {inserted, extracted}. */
    private static Map<Object, Map<Object, Map<AEKey, long[]>>> moved = new IdentityHashMap<>();

    private FlowRecorder() {}

    /** Turns recording on or off; the thread that will report (the server thread). */
    static void configure(boolean on, Thread thread) {
        serverThread = thread;
        if (!on) {
            enabled = false;
            moved = new IdentityHashMap<>();
        } else {
            enabled = true;
        }
    }

    /** One applied insert or extract. Called by the mixin. */
    public static void record(Object storage, AEKey key, long amount, IActionSource source, boolean insert) {
        if (!enabled || amount <= 0 || key == null || Thread.currentThread() != serverThread) return;
        Object who = UNKNOWN;
        if (source != null) {
            var machine = source.machine();
            if (machine.isPresent()) {
                who = machine.get();
            } else {
                var player = source.player();
                if (player.isPresent()) who = player.get();
            }
        }
        long[] counts = moved.computeIfAbsent(storage, s -> new IdentityHashMap<>())
                .computeIfAbsent(who, w -> new HashMap<>())
                .computeIfAbsent(key, k -> new long[2]);
        int slot = insert ? 0 : 1;
        long sum = counts[slot] + amount;
        counts[slot] = sum < 0 ? Long.MAX_VALUE : sum;
    }

    /** What was recorded since the last call, and a fresh start. Server thread. */
    static Map<Object, Map<Object, Map<AEKey, long[]>>> drain() {
        var result = moved;
        moved = new IdentityHashMap<>();
        return result;
    }
}
