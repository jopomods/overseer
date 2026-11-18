package io.github.jopojp.overseer.core.access;

import io.github.jopojp.overseer.core.provider.ResourceRef;
import java.util.UUID;

/** Overseer-side shares: a resource owner let another player view it (ADR-0011). */
public interface Shares {
    Shares NONE = (resource, player) -> false;

    /** Called on every access check; must be fast and thread-safe. */
    boolean isShared(ResourceRef resource, UUID player);
}
