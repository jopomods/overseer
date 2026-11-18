package io.github.jopojp.overseer.core.action;

import io.github.jopojp.overseer.core.provider.ResourceRef;
import java.util.List;
import java.util.Objects;

/**
 * Outcome of a world action (ADR-0016).
 *
 * @param status  what happened
 * @param message human-readable reason for anything but {@link Status#OK}; may be {@code null}
 * @param changes what was changed, old and new value; empty unless {@code OK}
 */
public record ActionResult(Status status, String message, List<Change> changes) {
    public enum Status {
        /** Applied. */
        OK,
        /** The parameters are wrong (unknown action, name too long, ...). */
        INVALID,
        /** The mod's rules do not allow this player to do it. */
        DENIED,
        /** The target does not exist (any more). */
        NOT_FOUND,
        /** The target exists but cannot be changed right now, e.g. its chunk is not loaded. */
        UNAVAILABLE,
        /** The server did not get to it in time or is shutting down; nothing was changed. */
        BUSY,
        /** The provider failed while applying it; the log has details. */
        FAILED
    }

    /**
     * One changed value.
     *
     * @param resource the changed resource (the target or a part of it, e.g. a device)
     * @param field    what changed, e.g. {@code name}, {@code priority}
     * @param before   old value as text, {@code null} if there was none
     * @param after    new value as text, {@code null} if it was removed
     */
    public record Change(ResourceRef resource, String field, String before, String after) {
        public Change {
            Objects.requireNonNull(resource, "resource");
            Objects.requireNonNull(field, "field");
        }
    }

    public ActionResult {
        Objects.requireNonNull(status, "status");
        changes = List.copyOf(changes);
    }

    public static ActionResult ok(List<Change> changes) {
        return new ActionResult(Status.OK, null, changes);
    }

    public static ActionResult error(Status status, String message) {
        if (status == Status.OK) throw new IllegalArgumentException("not an error");
        return new ActionResult(status, message, List.of());
    }

    public boolean isOk() {
        return status == Status.OK;
    }
}
