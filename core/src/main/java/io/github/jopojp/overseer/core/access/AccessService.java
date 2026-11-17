package io.github.jopojp.overseer.core.access;

import io.github.jopojp.overseer.core.auth.Principal;
import io.github.jopojp.overseer.core.provider.AccessPolicy;
import io.github.jopojp.overseer.core.provider.DataProvider;
import io.github.jopojp.overseer.core.provider.ProviderSnapshot;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.core.provider.Sample;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Permission checks (ADR-0008). Default deny:
 * <ol>
 *   <li>admins and super admins see everything;</li>
 *   <li>otherwise the provider's {@link AccessPolicy} decides per resource;</li>
 *   <li>resources of unknown providers are never visible to non-admins.</li>
 * </ol>
 * Thread-safe as long as the policies are.
 */
public final class AccessService {
    private final Map<String, AccessPolicy> policies;

    public AccessService(List<DataProvider> providers) {
        var map = new HashMap<String, AccessPolicy>();
        for (DataProvider provider : providers) map.put(provider.descriptor().id(), provider.access());
        this.policies = Map.copyOf(map);
    }

    public boolean canView(Principal principal, ResourceRef resource) {
        Objects.requireNonNull(principal);
        if (principal.isAdmin()) return true;
        AccessPolicy policy = policies.get(resource.provider());
        if (policy == null) return false;
        try {
            return policy.canView(principal.player().uuid(), resource);
        } catch (RuntimeException e) {
            return false; // a broken policy must not leak data
        }
    }

    /** The snapshot reduced to what the principal may see. */
    public ProviderSnapshot filter(Principal principal, ProviderSnapshot snapshot) {
        if (principal.isAdmin()) return snapshot;
        var resources = new HashMap<ResourceRef, Map<String, String>>();
        snapshot.resources().forEach((ref, attributes) -> {
            if (canView(principal, ref)) resources.put(ref, attributes);
        });
        List<Sample> samples = snapshot.samples().stream()
                .filter(s -> resources.containsKey(s.resource()))
                .toList();
        return new ProviderSnapshot(snapshot.provider(), snapshot.at(), resources, samples);
    }
}
