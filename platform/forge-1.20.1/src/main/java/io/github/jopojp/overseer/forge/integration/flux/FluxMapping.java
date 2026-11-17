package io.github.jopojp.overseer.forge.integration.flux;

import io.github.jopojp.overseer.core.provider.MetricDescriptor;
import io.github.jopojp.overseer.core.provider.MetricKind;
import io.github.jopojp.overseer.core.provider.ProviderSnapshot;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.core.provider.Sample;
import io.github.jopojp.overseer.core.time.Timestamp;
import io.github.jopojp.overseer.forge.integration.flux.FluxModel.Access;
import io.github.jopojp.overseer.forge.integration.flux.FluxModel.Device;
import io.github.jopojp.overseer.forge.integration.flux.FluxModel.Member;
import io.github.jopojp.overseer.forge.integration.flux.FluxModel.Network;
import io.github.jopojp.overseer.forge.integration.flux.FluxModel.Security;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Turns {@link FluxModel} into Overseer resources and answers access questions from the
 * last collected state (ADR-0005, ADR-0008). Pure: no Flux or Minecraft classes.
 *
 * <p>Resources:
 * <ul>
 *   <li>{@code flux/network/<id>}: name, color, owner, security, device counts;
 *       metrics energy_input, energy_output, buffer, energy, tick_micros</li>
 *   <li>{@code flux/member/<networkId>:<uuid>}: name, access</li>
 *   <li>{@code flux/device/<dimension>@x,y,z}: network, type, name, priority, limit, ...;
 *       metrics transfer, buffer</li>
 * </ul>
 */
public final class FluxMapping {
    public static final String PROVIDER = "flux";

    static final MetricDescriptor ENERGY_INPUT = new MetricDescriptor("energy_input", MetricKind.RATE, "FE/t", 0.02);
    static final MetricDescriptor ENERGY_OUTPUT = new MetricDescriptor("energy_output", MetricKind.RATE, "FE/t", 0.02);
    static final MetricDescriptor BUFFER = new MetricDescriptor("buffer", MetricKind.GAUGE, "FE", 0.02);
    static final MetricDescriptor ENERGY = new MetricDescriptor("energy", MetricKind.GAUGE, "FE", 0.01);
    static final MetricDescriptor TICK_MICROS = new MetricDescriptor("tick_micros", MetricKind.GAUGE, "us", 0.25);
    static final MetricDescriptor TRANSFER = new MetricDescriptor("transfer", MetricKind.RATE, "FE/t", 0.02);

    static final List<MetricDescriptor> METRICS =
            List.of(ENERGY_INPUT, ENERGY_OUTPUT, BUFFER, ENERGY, TICK_MICROS, TRANSFER);

    private FluxMapping() {}

    public static ResourceRef network(int id) {
        return new ResourceRef(PROVIDER, "network", Integer.toString(id));
    }

    public static ResourceRef member(int networkId, UUID player) {
        return new ResourceRef(PROVIDER, "member", networkId + ":" + player);
    }

    public static ResourceRef device(Device device) {
        return new ResourceRef(PROVIDER, "device", device.id());
    }

    public static ProviderSnapshot snapshot(Timestamp at, List<Network> networks) {
        Map<ResourceRef, Map<String, String>> resources = new HashMap<>();
        List<Sample> samples = new ArrayList<>();
        for (Network n : networks) {
            ResourceRef ref = network(n.id());
            var attrs = new LinkedHashMap<String, String>();
            attrs.put("name", n.name());
            attrs.put("color", String.format(Locale.ROOT, "#%06x", n.color() & 0xFFFFFF));
            attrs.put("owner", n.owner() == null ? "" : n.owner().toString());
            attrs.put("security", n.security().name().toLowerCase(Locale.ROOT));
            attrs.put("plugs", Integer.toString(n.stats().plugs()));
            attrs.put("points", Integer.toString(n.stats().points()));
            attrs.put("storages", Integer.toString(n.stats().storages()));
            attrs.put("controllers", Integer.toString(n.stats().controllers()));
            resources.put(ref, attrs);
            samples.add(new Sample(ref, ENERGY_INPUT.key(), n.stats().energyInput()));
            samples.add(new Sample(ref, ENERGY_OUTPUT.key(), n.stats().energyOutput()));
            samples.add(new Sample(ref, BUFFER.key(), n.stats().totalBuffer()));
            samples.add(new Sample(ref, ENERGY.key(), n.stats().totalEnergy()));
            samples.add(new Sample(ref, TICK_MICROS.key(), n.stats().averageTickMicros()));

            for (Member m : n.members()) {
                resources.put(member(n.id(), m.uuid()), Map.of(
                        "network", Integer.toString(n.id()),
                        "player", m.uuid().toString(),
                        "name", m.name() == null ? "" : m.name(),
                        "access", m.access().name().toLowerCase(Locale.ROOT)));
            }

            for (Device d : n.devices()) {
                ResourceRef dref = device(d);
                var dattrs = new LinkedHashMap<String, String>();
                dattrs.put("network", Integer.toString(n.id()));
                dattrs.put("type", d.type().name().toLowerCase(Locale.ROOT));
                dattrs.put("name", d.name() == null ? "" : d.name());
                dattrs.put("dimension", d.dimension());
                dattrs.put("pos", d.x() + "," + d.y() + "," + d.z());
                dattrs.put("owner", d.owner() == null ? "" : d.owner().toString());
                dattrs.put("priority", Integer.toString(d.priority()));
                dattrs.put("limit", Long.toString(d.limit()));
                dattrs.put("maxLimit", Long.toString(d.maxLimit()));
                dattrs.put("disableLimit", Boolean.toString(d.disableLimit()));
                dattrs.put("surge", Boolean.toString(d.surge()));
                dattrs.put("loaded", Boolean.toString(d.chunkLoaded()));
                dattrs.put("forcedLoading", Boolean.toString(d.forcedLoading()));
                resources.put(dref, dattrs);
                samples.add(new Sample(dref, TRANSFER.key(), d.change()));
                samples.add(new Sample(dref, BUFFER.key(), d.buffer()));
            }
        }
        return new ProviderSnapshot(PROVIDER, at, resources, samples);
    }

    /**
     * Who may see what, computed from one collection. Mirrors Flux's own rule: members
     * use their access level; non-members get {@code USER} on public networks, otherwise
     * nothing. Blocked players see nothing.
     */
    public static final class AccessIndex {
        public static final AccessIndex EMPTY = new AccessIndex(Map.of(), Map.of(), Map.of());

        private final Map<Integer, Map<UUID, Access>> members;
        private final Map<Integer, Security> security;
        private final Map<String, Integer> networkOfDevice;

        private AccessIndex(
                Map<Integer, Map<UUID, Access>> members,
                Map<Integer, Security> security,
                Map<String, Integer> networkOfDevice) {
            this.members = members;
            this.security = security;
            this.networkOfDevice = networkOfDevice;
        }

        public static AccessIndex of(List<Network> networks) {
            var members = new HashMap<Integer, Map<UUID, Access>>();
            var security = new HashMap<Integer, Security>();
            var devices = new HashMap<String, Integer>();
            for (Network n : networks) {
                var byPlayer = new HashMap<UUID, Access>();
                for (Member m : n.members()) byPlayer.put(m.uuid(), m.access());
                members.put(n.id(), Map.copyOf(byPlayer));
                security.put(n.id(), n.security());
                for (Device d : n.devices()) devices.put(d.id(), n.id());
            }
            return new AccessIndex(Map.copyOf(members), Map.copyOf(security), Map.copyOf(devices));
        }

        public boolean canView(UUID player, ResourceRef ref) {
            Integer networkId = networkOf(ref);
            return networkId != null && access(player, networkId).canView();
        }

        Access access(UUID player, int networkId) {
            Access member = members.getOrDefault(networkId, Map.of()).get(player);
            if (member != null) return member;
            return security.get(networkId) == Security.PUBLIC ? Access.USER : Access.BLOCKED;
        }

        private Integer networkOf(ResourceRef ref) {
            if (!ref.provider().equals(PROVIDER)) return null;
            try {
                return switch (ref.type()) {
                    case "network" -> Integer.valueOf(ref.id());
                    case "member" -> Integer.valueOf(ref.id().substring(0, ref.id().indexOf(':')));
                    case "device" -> networkOfDevice.get(ref.id());
                    default -> null;
                };
            } catch (RuntimeException e) {
                return null;
            }
        }
    }
}
