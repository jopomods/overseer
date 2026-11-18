package io.github.jopojp.overseer.core.action;

import com.google.gson.JsonObject;
import io.github.jopojp.overseer.core.access.AccessService;
import io.github.jopojp.overseer.core.auth.Principal;
import io.github.jopojp.overseer.core.auth.Role;
import io.github.jopojp.overseer.core.provider.DataProvider;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.core.storage.ActionLogStore;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs world actions for dashboard users (ADR-0016): checks who may do what, hands the action to
 * the server thread and logs the outcome.
 *
 * <p>Who may act: never device tokens; server operators (live check) on everything they can
 * see; everyone else only where the provider's mod rules allow it ({@link ActionHandler#canPerform}).
 * Dashboard admins who are not operators get no extra rights here: seeing everything is not
 * changing everything.
 */
public final class ActionService {
    private static final Logger LOG = LoggerFactory.getLogger(ActionService.class);

    /** Parameter names whose values never reach the log. */
    static final Set<String> SECRET_PARAMS = Set.of("password");

    private final Map<String, ActionHandler> handlers;
    private final AccessService access;
    private final ActionQueue queue;
    private final ActionLogStore log;
    private final Clock clock;
    private final Duration timeout;

    public ActionService(List<DataProvider> providers, AccessService access, ActionQueue queue, ActionLogStore log,
            Clock clock, Duration timeout) {
        var map = new HashMap<String, ActionHandler>();
        for (DataProvider provider : providers) {
            ActionHandler handler = provider.actions();
            if (handler != null && !handler.types().isEmpty()) map.put(provider.descriptor().id(), handler);
        }
        this.handlers = Map.copyOf(map);
        this.access = Objects.requireNonNull(access);
        this.queue = Objects.requireNonNull(queue);
        this.log = Objects.requireNonNull(log);
        this.clock = Objects.requireNonNull(clock);
        this.timeout = Objects.requireNonNull(timeout);
    }

    /** Whether the principal may run this action type on the target (for the UI). */
    public boolean canPerform(Principal principal, ResourceRef target, String type) {
        if (principal.deviceToken()) return false;
        ActionHandler handler = handlers.get(target.provider());
        if (handler == null || !handler.types().contains(type)) return false;
        if (!access.canView(principal, target)) return false;
        if (principal.role() == Role.SUPER_ADMIN) return true;
        try {
            return handler.canPerform(principal.player().uuid(), target, type);
        } catch (RuntimeException e) {
            return false; // a broken policy must not grant anything
        }
    }

    /** The action types a principal may run on a target; empty for most viewers. */
    public Set<String> allowed(Principal principal, ResourceRef target) {
        ActionHandler handler = handlers.get(target.provider());
        if (handler == null || principal.deviceToken()) return Set.of();
        var allowed = new java.util.TreeSet<String>();
        for (String type : handler.types()) {
            if (canPerform(principal, target, type)) allowed.add(type);
        }
        return allowed;
    }

    /**
     * Checks, runs and logs one action. Blocks until the server thread has run it or the
     * timeout passed. Every outcome after the target was found is logged, refusals included.
     */
    public ActionResult perform(Principal principal, ActionRequest request) throws SQLException {
        ActionHandler handler = handlers.get(request.target().provider());
        if (handler == null || !handler.types().contains(request.type())) {
            return ActionResult.error(ActionResult.Status.INVALID, "Unknown action.");
        }
        if (!access.canView(principal, request.target())) {
            return ActionResult.error(ActionResult.Status.NOT_FOUND, "Not found.");
        }
        var actor = new Actor(principal.player().uuid(), principal.player().name(),
                principal.role() == Role.SUPER_ADMIN);
        ActionResult result;
        if (principal.deviceToken()) {
            result = ActionResult.error(ActionResult.Status.DENIED, "Device tokens cannot change anything.");
        } else if (!canPerform(principal, request.target(), request.type())) {
            result = ActionResult.error(ActionResult.Status.DENIED, "You may not change this.");
        } else {
            result = queue.await(queue.submit(() -> handler.perform(actor, request)), timeout);
        }
        var logged = new ActionRequest(request.type(), request.target(), redact(request.params()));
        try {
            log.record(clock.instant(), actor, logged, result);
        } catch (SQLException e) {
            // The change already happened; failing the request now would only make the user retry it.
            LOG.error("Overseer could not log world action {} on {} by {}", request.type(), request.target().key(),
                    actor.name(), e);
        }
        if (result.isOk()) {
            LOG.info("Overseer: {} ran {} on {} ({} changes)", actor.name(), request.type(), request.target().key(),
                    result.changes().size());
        }
        return result;
    }

    static JsonObject redact(JsonObject params) {
        var copy = params.deepCopy();
        for (String key : Set.copyOf(copy.keySet())) {
            if (SECRET_PARAMS.contains(key.toLowerCase(Locale.ROOT))) copy.addProperty(key, "***");
        }
        return copy;
    }
}
