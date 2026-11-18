package io.github.jopojp.overseer.core.action;

import io.github.jopojp.overseer.core.provider.ResourceRef;
import java.util.Set;
import java.util.UUID;

/**
 * The world actions one provider offers (ADR-0016). Optional: providers without actions
 * use {@link #NONE}.
 *
 * <p>Permissions are checked twice. {@link #canPerform} answers from snapshot data on an HTTP
 * thread so the request can be refused early; {@link #perform} checks again against live mod
 * state on the server thread, because membership may have changed in between.
 */
public interface ActionHandler {
    /** Action types this provider handles, e.g. {@code flux.network.edit}. */
    Set<String> types();

    /**
     * Whether the mod lets this player run this action on this target. Operators are allowed
     * by core before this is asked. Any thread; snapshot data only. Default deny.
     */
    boolean canPerform(UUID player, ResourceRef target, String type);

    /**
     * Applies the action. <b>Server thread only</b> (called from the action queue). Must
     * check permissions again against live state ({@code actor.operator()} may do anything),
     * validate every parameter and either change nothing or report every change.
     */
    ActionResult perform(Actor actor, ActionRequest request);

    ActionHandler NONE = new ActionHandler() {
        @Override
        public Set<String> types() {
            return Set.of();
        }

        @Override
        public boolean canPerform(UUID player, ResourceRef target, String type) {
            return false;
        }

        @Override
        public ActionResult perform(Actor actor, ActionRequest request) {
            return ActionResult.error(ActionResult.Status.INVALID, "This provider has no actions.");
        }
    };
}
