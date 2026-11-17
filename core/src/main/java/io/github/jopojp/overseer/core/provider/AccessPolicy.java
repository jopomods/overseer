package io.github.jopojp.overseer.core.provider;

import java.util.UUID;

/**
 * Resource-level permission check of one provider (ADR-0008).
 *
 * <p>Default deny: return {@code false} unless the mod's own rules grant the player
 * access. Global roles (op, dashboard admin) are handled by core before this is asked,
 * so implementations only answer "does the mod allow it?".
 */
@FunctionalInterface
public interface AccessPolicy {
    boolean canView(UUID player, ResourceRef resource);

    /** For providers without per-resource rules: everyone who is logged in may view. */
    AccessPolicy ALLOW_AUTHENTICATED = (player, resource) -> true;
}
