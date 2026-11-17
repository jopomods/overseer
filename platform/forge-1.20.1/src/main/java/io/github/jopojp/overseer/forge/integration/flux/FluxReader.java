package io.github.jopojp.overseer.forge.integration.flux;

import io.github.jopojp.overseer.forge.integration.flux.FluxModel.Access;
import io.github.jopojp.overseer.forge.integration.flux.FluxModel.Device;
import io.github.jopojp.overseer.forge.integration.flux.FluxModel.DeviceType;
import io.github.jopojp.overseer.forge.integration.flux.FluxModel.Member;
import io.github.jopojp.overseer.forge.integration.flux.FluxModel.Network;
import io.github.jopojp.overseer.forge.integration.flux.FluxModel.Security;
import io.github.jopojp.overseer.forge.integration.flux.FluxModel.Stats;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.GlobalPos;
import sonar.fluxnetworks.api.device.IFluxDevice;
import sonar.fluxnetworks.api.network.NetworkMember;
import sonar.fluxnetworks.common.connection.FluxNetwork;
import sonar.fluxnetworks.common.connection.FluxNetworkData;
import sonar.fluxnetworks.common.connection.NetworkStatistics;

/**
 * The only class that reads Flux Networks objects (Flux Networks 7.2 for 1.20.1).
 * Server thread only. Copies everything into {@link FluxModel}; no Flux object escapes.
 */
final class FluxReader {
    private FluxReader() {}

    static List<Network> readAll() {
        var result = new ArrayList<Network>();
        for (FluxNetwork network : FluxNetworkData.getAllNetworks()) {
            if (network == null || !network.isValid()) continue;
            result.add(read(network));
        }
        return result;
    }

    private static Network read(FluxNetwork network) {
        NetworkStatistics s = network.getStatistics();
        var stats = new Stats(s.energyInput, s.energyOutput, s.totalBuffer, s.totalEnergy, s.averageTickMicro,
                s.fluxPlugCount, s.fluxPointCount, s.fluxStorageCount, s.fluxControllerCount);

        var members = new ArrayList<Member>();
        for (NetworkMember member : network.getAllMembers()) {
            members.add(new Member(member.getPlayerUUID(), member.getCachedName(),
                    Access.valueOf(member.getAccessLevel().name())));
        }

        var devices = new ArrayList<Device>();
        for (IFluxDevice device : network.getAllConnections()) {
            GlobalPos pos = device.getGlobalPos();
            if (pos == null) continue;
            devices.add(new Device(
                    pos.dimension().location().toString(),
                    pos.pos().getX(), pos.pos().getY(), pos.pos().getZ(),
                    DeviceType.valueOf(device.getDeviceType().name()),
                    device.getCustomName(),
                    device.getOwnerUUID(),
                    device.getRawPriority(),
                    device.getRawLimit(),
                    device.getMaxTransferLimit(),
                    device.getDisableLimit(),
                    device.getSurgeMode(),
                    device.isChunkLoaded(),
                    device.isForcedLoading(),
                    device.getTransferBuffer(),
                    device.getTransferChange()));
        }

        return new Network(network.getNetworkID(), network.getNetworkName(), network.getNetworkColor(),
                network.getOwnerUUID(), Security.valueOf(network.getSecurityLevel().name()), stats, members, devices);
    }
}
