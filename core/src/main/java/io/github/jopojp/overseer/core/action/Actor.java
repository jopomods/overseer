package io.github.jopojp.overseer.core.action;

import java.util.Objects;
import java.util.UUID;

/**
 * Who asked for a world action (ADR-0016).
 *
 * @param player   the player's UUID
 * @param name     the player's name when the action was requested
 * @param operator a server operator at request time; providers let operators do anything the
 *                 mod's owner could do
 */
public record Actor(UUID player, String name, boolean operator) {
    public Actor {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(name, "name");
    }
}
