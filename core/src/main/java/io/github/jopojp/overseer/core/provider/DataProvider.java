package io.github.jopojp.overseer.core.provider;

import io.github.jopojp.overseer.core.time.Timestamp;

/**
 * The contract every integration implements (ADR-0002, ADR-0004).
 *
 * <p>Implementations live in a platform module (they need Minecraft and the mod API);
 * core only sees this interface. A provider whose mod is not installed is never
 * class-loaded.
 *
 * <p><b>Threading:</b> {@link #collect(Timestamp)} is called on the server thread and is
 * the only place allowed to read world or mod state. It must return a fully built,
 * immutable snapshot and should stay within {@link ProviderDescriptor#budgetNanos()}.
 * {@link #access()} may be called from any thread and must only use data from snapshots.
 *
 * <p>Status: draft contract; may change until 0.1.0.
 */
public interface DataProvider {

    ProviderDescriptor descriptor();

    /**
     * Reads current state. Server thread only.
     *
     * <p>The returned snapshot must list <em>every</em> resource the provider currently
     * knows (including unloaded ones it still tracks). A resource that disappears from
     * the snapshot is recorded as removed.
     *
     * @param at the timestamp of this collection; use it as the snapshot's {@code at}
     */
    ProviderSnapshot collect(Timestamp at);

    /** Resource-level access rules for this provider (ADR-0008). */
    AccessPolicy access();
}
