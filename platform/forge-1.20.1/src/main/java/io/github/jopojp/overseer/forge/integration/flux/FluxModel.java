package io.github.jopojp.overseer.forge.integration.flux;

import java.util.List;
import java.util.UUID;

/**
 * Plain copies of Flux Networks state, read on the server thread by {@link FluxReader}.
 * Free of Flux and Minecraft types so the mapping and access rules can be unit-tested.
 */
public final class FluxModel {
    private FluxModel() {}

    /** Flux access levels, mirrored by name. */
    public enum Access {
        OWNER, ADMIN, USER, BLOCKED, SUPER_ADMIN;

        public boolean canView() {
            return this != BLOCKED;
        }
    }

    /** Flux security levels, mirrored by name. */
    public enum Security { PUBLIC, ENCRYPTED, PRIVATE }

    public enum DeviceType { PLUG, POINT, STORAGE, CONTROLLER }

    /** Energy values are FE (Forge Energy); flows are FE per tick. */
    public record Stats(
            long energyInput,
            long energyOutput,
            long totalBuffer,
            long totalEnergy,
            int averageTickMicros,
            int plugs,
            int points,
            int storages,
            int controllers) {}

    public record Member(UUID uuid, String name, Access access) {}

    /**
     * @param dimension    e.g. {@code minecraft:overworld}
     * @param limit        configured transfer limit (FE/t)
     * @param maxLimit     hardware maximum (FE/t)
     * @param chunkLoaded  {@code false} for devices Flux only remembers (unloaded chunk)
     * @param buffer       internal buffer; for storages the stored energy (FE)
     * @param change       last transfer (FE/t) as reported by Flux
     */
    public record Device(
            String dimension,
            int x,
            int y,
            int z,
            DeviceType type,
            String name,
            UUID owner,
            int priority,
            long limit,
            long maxLimit,
            boolean disableLimit,
            boolean surge,
            boolean chunkLoaded,
            boolean forcedLoading,
            long buffer,
            long change) {

        /** Stable id: dimension and block position (ADR-0005). */
        public String id() {
            return dimension + "@" + x + "," + y + "," + z;
        }
    }

    public record Network(
            int id,
            String name,
            int color,
            UUID owner,
            Security security,
            Stats stats,
            List<Member> members,
            List<Device> devices) {
        public Network {
            members = List.copyOf(members);
            devices = List.copyOf(devices);
        }
    }
}
