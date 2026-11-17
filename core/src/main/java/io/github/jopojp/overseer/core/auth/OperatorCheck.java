package io.github.jopojp.overseer.core.auth;

import java.util.UUID;

/**
 * Tells core whether a player is a server operator (permission level >= 2).
 * Supplied by the platform. Called from HTTP threads, so implementations must be
 * thread-safe and should answer from a snapshot refreshed on the server thread.
 */
@FunctionalInterface
public interface OperatorCheck {
    boolean isOperator(UUID player);

    OperatorCheck NONE = player -> false;
}
