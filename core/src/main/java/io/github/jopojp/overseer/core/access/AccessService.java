package io.github.jopojp.overseer.core.access;

import io.github.jopojp.overseer.core.auth.Principal;
import io.github.jopojp.overseer.core.provider.AccessPolicy;
import io.github.jopojp.overseer.core.provider.Capability;
import io.github.jopojp.overseer.core.provider.DataProvider;
import io.github.jopojp.overseer.core.provider.ProviderSnapshot;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.core.provider.Sample;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Permission checks (ADR-0008). Default deny:
 * <ol>
 *   <li>admins and super admins see everything;</li>
 *   <li>otherwise the provider's {@link AccessPolicy} decides per resource;</li>
 *   <li>for providers with {@link Capability#SHARING}, Overseer shares also grant viewing;</li>
 *   <li>resources of unknown providers are never visible to non-admins.</li>
 * </ol>
 * Thread-safe as long as the policies are.
 */
public final class AccessService {
    private final Map<String, AccessPolicy> policies;
    private final Set<String> sharing;
    private final Shares shares;

    public AccessService(List<DataProvider> providers) {
        this(providers, Shares.NONE);
    }

    public AccessService(List<DataProvider> providers, Shares shares) {
        var map = new HashMap<String, AccessPolicy>();
        var sharable = new HashSet<String>();
        for (DataProvider provider : providers) {
            map.put(provider.descriptor().id(), provider.access());
            if (provider.descriptor().capabilities().contains(Capability.SHARING)) {
                sharable.add(provider.descriptor().id());
            }
        }
        this.policies = Map.copyOf(map);
        this.sharing = Set.copyOf(sharable);
        this.shares = shares;
    }

    /** Whether resources of this provider can be shared in Overseer. */
    public boolean isShareable(ResourceRef resource) {
        return sharing.contains(resource.provider());
    }

    public boolean canView(Principal principal, ResourceRef resource) {
        Objects.requireNonNull(principal);
        if (principal.isAdmin()) return true;
        AccessPolicy policy = policies.get(resource.provider());
        if (policy == null) return false;
        try {
            UUID player = principal.player().uuid();
            if (policy.canView(player, resource)) return true;
            return sharing.contains(resource.provider()) && shares.isShared(resource, player);
        } catch (RuntimeException e) {
            return false; // a broken policy must not leak data
        }
    }

    /**
     * Admins may edit everything; otherwise the provider decides. Never more than viewing
     * allows. Shares never grant editing.
     */
    public boolean canEdit(Principal principal, ResourceRef resource) {
        if (principal.deviceToken()) return false;
        if (principal.isAdmin()) return true;
        if (!canView(principal, resource)) return false;
        AccessPolicy policy = policies.get(resource.provider());
        try {
            return policy != null && policy.canEdit(principal.player().uuid(), resource);
        } catch (RuntimeException e) {
            return false;
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
