package io.github.jopojp.overseer.core.action;

import com.google.gson.JsonObject;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import java.util.List;
import java.util.Objects;

/**
 * Outcome of a world action (ADR-0016).
 *
 * @param status  what happened
 * @param message human-readable reason for anything but {@link Status#OK}; may be {@code null}
 * @param changes what was changed, old and new value; empty unless {@code OK}
 * @param data    what the action found out for the caller, e.g. a crafting plan; {@code null} if
 *                nothing. Sent back, never logged.
 */
public record ActionResult(Status status, String message, List<Change> changes, JsonObject data) {
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
        if (data != null) data = data.deepCopy();
    }

    public ActionResult(Status status, String message, List<Change> changes) {
        this(status, message, changes, null);
    }

    /** A copy, so nobody changes the answer after the fact. */
    @Override
    public JsonObject data() {
        return data == null ? null : data.deepCopy();
    }

    public static ActionResult ok(List<Change> changes) {
        return new ActionResult(Status.OK, null, changes);
    }

    /** Applied (or nothing to apply), with an answer for the caller. */
    public static ActionResult ok(List<Change> changes, JsonObject data) {
        return new ActionResult(Status.OK, null, changes, data);
    }

    public static ActionResult error(Status status, String message) {
        if (status == Status.OK) throw new IllegalArgumentException("not an error");
        return new ActionResult(status, message, List.of());
    }

    public boolean isOk() {
        return status == Status.OK;
    }
}
