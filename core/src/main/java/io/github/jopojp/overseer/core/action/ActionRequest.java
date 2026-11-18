package io.github.jopojp.overseer.core.action;

import com.google.gson.JsonObject;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import java.util.Objects;

/**
 * A request to change something in the world (ADR-0016).
 *
 * @param type   action type of the target's provider, e.g. {@code flux.network.edit}
 * @param target the resource the action is about; permissions are checked against it
 * @param params action-specific parameters as sent by the client; validated by the provider
 */
public record ActionRequest(String type, ResourceRef target, JsonObject params) {
    public ActionRequest {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(target, "target");
        params = params == null ? new JsonObject() : params.deepCopy();
    }

    /** A copy, so a handler cannot change what is logged. */
    @Override
    public JsonObject params() {
        return params.deepCopy();
    }
}
