package io.github.jopojp.overseer.forge.integration.flux;

import com.mojang.authlib.GameProfile;
import io.github.jopojp.overseer.core.action.ActionResult;
import io.github.jopojp.overseer.core.action.ActionResult.Change;
import io.github.jopojp.overseer.core.action.ActionResult.Status;
import io.github.jopojp.overseer.core.action.Actor;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.forge.integration.flux.FluxActions.DeviceEdit;
import io.github.jopojp.overseer.forge.integration.flux.FluxActions.DevicePos;
import io.github.jopojp.overseer.forge.integration.flux.FluxActions.MemberEdit;
import io.github.jopojp.overseer.forge.integration.flux.FluxActions.NetworkEdit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sonar.fluxnetworks.FluxConfig;
import sonar.fluxnetworks.api.FluxConstants;
import sonar.fluxnetworks.api.device.IFluxDevice;
import sonar.fluxnetworks.api.network.AccessLevel;
import sonar.fluxnetworks.api.network.NetworkMember;
import sonar.fluxnetworks.api.network.SecurityLevel;
import sonar.fluxnetworks.common.connection.FluxNetwork;
import sonar.fluxnetworks.common.connection.FluxNetworkData;
import sonar.fluxnetworks.common.connection.ServerFluxNetwork;
import sonar.fluxnetworks.common.device.TileFluxDevice;
import sonar.fluxnetworks.register.Channel;
import sonar.fluxnetworks.register.Messages;

/**
 * The only class that changes Flux Networks state (ADR-0016). Server thread only. Uses the same
 * calls as Flux's own GUI packets, so Flux's own rules and side effects apply; permissions are
 * checked again here against live state, because the snapshot may be a second old.
 *
 * <p>Operators act with the network owner's rights. Changes are reported with their effect as
 * read back from Flux, not as requested.
 */
final class FluxWriter {
    private static final Logger LOG = LoggerFactory.getLogger(FluxWriter.class);

    private FluxWriter() {}

    // --- network ---------------------------------------------------------------------------

    static ActionResult editNetwork(Actor actor, int networkId, NetworkEdit edit) {
        FluxNetwork network = FluxNetworkData.getNetwork(networkId);
        if (!network.isValid()) return notFound();
        if (!access(network, actor).canEdit()) return denied();
        ResourceRef ref = FluxMapping.network(networkId);
        var changes = new ArrayList<Change>();

        if (edit.name() != null && !edit.name().equals(network.getNetworkName())) {
            String before = network.getNetworkName();
            if (!network.setNetworkName(edit.name())) return invalid("Flux refused the name.");
            changes.add(new Change(ref, "name", before, network.getNetworkName()));
        }
        if (edit.color() != null && (edit.color() & 0xFFFFFF) != (network.getNetworkColor() & 0xFFFFFF)) {
            String before = color(network.getNetworkColor());
            network.setNetworkColor(edit.color());
            // As Flux does: devices show the network colour, so they need a block update.
            network.getLogicalDevices(FluxNetwork.ANY).forEach(TileFluxDevice::sendBlockUpdate);
            changes.add(new Change(ref, "color", before, color(network.getNetworkColor())));
        }
        if (edit.security() != null) {
            SecurityLevel level = SecurityLevel.valueOf(edit.security().name());
            SecurityLevel before = network.getSecurityLevel();
            if (level != before) {
                network.setSecurityLevel(level);
                changes.add(new Change(ref, "security", lower(before.name()), lower(network.getSecurityLevel().name())));
            }
        }
        if (edit.password() != null && network instanceof ServerFluxNetwork server) {
            server.setPassword(edit.password());
            changes.add(new Change(ref, "password", null, "***"));
        }
        if (!changes.isEmpty()) broadcast(network, FluxConstants.NBT_NET_BASIC);
        return ActionResult.ok(changes);
    }

    // --- devices ---------------------------------------------------------------------------

    static ActionResult editDevices(Actor actor, int networkId, List<DeviceEdit> edits) {
        FluxNetwork network = FluxNetworkData.getNetwork(networkId);
        if (!network.isValid()) return notFound();
        if (!access(network, actor).canEdit()) return denied();

        // Resolve and check everything first, so a wrong entry changes nothing.
        var loaded = new ArrayList<TileFluxDevice>();
        var loadedEdits = new ArrayList<DeviceEdit>();
        int unloaded = 0;
        for (DeviceEdit edit : edits) {
            IFluxDevice device = network.getConnectionByPos(globalPos(edit.pos()));
            if (device == null) return invalid("Device " + edit.pos().id() + " is not in this network.");
            if (!(device instanceof TileFluxDevice tile)) {
                unloaded++;
                continue;
            }
            if (Boolean.TRUE.equals(edit.chunkLoading())) {
                if (!FluxConfig.enableChunkLoading) return invalid("Chunk loading is switched off on this server.");
                if (tile.getDeviceType().isStorage()) return invalid("Flux storages cannot load chunks.");
            }
            loaded.add(tile);
            loadedEdits.add(edit);
        }
        if (loaded.isEmpty()) {
            return ActionResult.error(Status.UNAVAILABLE,
                    "The devices are in unloaded chunks. Flux can only change loaded devices.");
        }

        var changes = new ArrayList<Change>();
        for (int i = 0; i < loaded.size(); i++) {
            TileFluxDevice tile = loaded.get(i);
            DeviceEdit edit = loadedEdits.get(i);
            Settings before = Settings.of(tile);
            var tag = new CompoundTag();
            if (edit.name() != null) tag.putString(FluxConstants.CUSTOM_NAME, edit.name());
            if (edit.priority() != null) tag.putInt(FluxConstants.PRIORITY, edit.priority());
            if (edit.limit() != null) tag.putLong(FluxConstants.LIMIT, edit.limit());
            if (edit.disableLimit() != null) tag.putBoolean(FluxConstants.DISABLE_LIMIT, edit.disableLimit());
            if (edit.surge() != null) tag.putBoolean(FluxConstants.SURGE_MODE, edit.surge());
            if (edit.chunkLoading() != null) tag.putBoolean(FluxConstants.FORCED_LOADING, edit.chunkLoading());
            tile.readCustomTag(tag, FluxConstants.NBT_TILE_SETTINGS);
            before.diff(Settings.of(tile), deviceRef(edit.pos()), changes);
        }
        String message = unloaded == 0 ? null
                : unloaded + (unloaded == 1 ? " device was" : " devices were") + " skipped: not loaded.";
        return new ActionResult(Status.OK, message, changes);
    }

    /** Device settings as Flux reports them. */
    private record Settings(String name, int priority, long limit, boolean disableLimit, boolean surge,
            boolean chunkLoading) {
        static Settings of(TileFluxDevice tile) {
            return new Settings(tile.getCustomName(), tile.getRawPriority(), tile.getRawLimit(), tile.getDisableLimit(),
                    tile.getSurgeMode(), tile.isForcedLoading());
        }

        void diff(Settings after, ResourceRef ref, List<Change> changes) {
            add(changes, ref, "name", name, after.name);
            add(changes, ref, "priority", priority, after.priority);
            add(changes, ref, "limit", limit, after.limit);
            add(changes, ref, "disableLimit", disableLimit, after.disableLimit);
            add(changes, ref, "surge", surge, after.surge);
            add(changes, ref, "chunkLoading", chunkLoading, after.chunkLoading);
        }

        private static void add(List<Change> changes, ResourceRef ref, String field, Object before, Object after) {
            if (!Objects.equals(before, after)) {
                changes.add(new Change(ref, field, String.valueOf(before), String.valueOf(after)));
            }
        }
    }

    /** Connects loaded devices to the network, like choosing it in the device's GUI. */
    static ActionResult connect(Actor actor, int networkId, List<DevicePos> devices) {
        FluxNetwork target = FluxNetworkData.getNetwork(networkId);
        if (!target.isValid()) return notFound();
        if (!access(target, actor).canEdit()) return denied();
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();

        var tiles = new ArrayList<TileFluxDevice>();
        int unloaded = 0;
        boolean controller = !target.getLogicalDevices(FluxNetwork.CONTROLLER).isEmpty();
        for (DevicePos pos : devices) {
            ServerLevel level = server.getLevel(dimension(pos.dimension()));
            BlockPos block = new BlockPos(pos.x(), pos.y(), pos.z());
            if (level == null || !level.isLoaded(block)) {
                unloaded++;
                continue;
            }
            if (!(level.getBlockEntity(block) instanceof TileFluxDevice tile)) {
                return invalid("There is no Flux device at " + pos.id() + ".");
            }
            if (tile.getNetworkID() == networkId) continue;
            FluxNetwork source = tile.getNetwork();
            boolean ownsDevice = actor.player().equals(tile.getOwnerUUID());
            if (source.isValid() && !ownsDevice && !access(source, actor).canEdit()) {
                return ActionResult.error(Status.DENIED, "You may not move devices out of network "
                        + source.getNetworkName() + ".");
            }
            if (tile.getDeviceType().isController()) {
                if (controller) return invalid("A network can have only one controller.");
                controller = true;
            }
            tiles.add(tile);
        }
        if (tiles.isEmpty() && unloaded > 0) {
            return ActionResult.error(Status.UNAVAILABLE,
                    "The devices are in unloaded chunks. Flux can only change loaded devices.");
        }
        var changes = new ArrayList<Change>();
        for (TileFluxDevice tile : tiles) {
            int before = tile.getNetworkID();
            tile.setOwnerUUID(actor.player()); // as Flux does: whoever connects a device owns it
            if (!tile.connect(target)) continue;
            GlobalPos at = tile.getGlobalPos();
            changes.add(new Change(deviceRef(new DevicePos(at.dimension().location().toString(), at.pos().getX(),
                    at.pos().getY(), at.pos().getZ())), "network", Integer.toString(before), Integer.toString(networkId)));
        }
        String message = unloaded == 0 ? null
                : unloaded + (unloaded == 1 ? " device was" : " devices were") + " skipped: not loaded.";
        return new ActionResult(Status.OK, message, changes);
    }

    // --- members ---------------------------------------------------------------------------

    static ActionResult editMember(Actor actor, int networkId, MemberEdit edit) {
        FluxNetwork network = FluxNetworkData.getNetwork(networkId);
        if (!network.isValid()) return notFound();
        AccessLevel actorAccess = access(network, actor);
        if (!actorAccess.canEdit()) return denied();
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();

        Optional<GameProfile> profile = edit.player() != null ? profile(server, network, edit.player(), edit.name())
                : profileByName(server, network, edit.name());
        if (profile.isEmpty()) {
            return invalid("Unknown player. They must have joined this server at least once.");
        }
        UUID target = profile.get().getId();
        String targetName = profile.get().getName();
        String before = memberAccess(network, target);

        if (edit.role() == FluxActions.Role.REMOVE && network.getMemberByUUID(target) == null) {
            return invalid(targetName + " is not a member.");
        }
        // Flux adds only players who are online; the dashboard also adds offline ones, with the
        // same permission rule (owners and admins may add users).
        if (edit.role() != FluxActions.Role.REMOVE && network.getMemberByUUID(target) == null
                && server.getPlayerList().getPlayer(target) == null) {
            if ((edit.role() != FluxActions.Role.USER) && !actorAccess.canDelete()) {
                return ActionResult.error(Status.DENIED, "Only the owner can make admins or hand over the network.");
            }
            addOfflineMember(network, target, targetName);
            if (edit.role() == FluxActions.Role.USER) {
                return ActionResult.ok(List.of(new Change(FluxMapping.member(networkId, target), "access", before, "user")));
            }
        }

        byte type = switch (edit.role()) {
            case USER -> FluxConstants.MEMBERSHIP_SET_USER;
            case ADMIN -> FluxConstants.MEMBERSHIP_SET_ADMIN;
            case REMOVE -> FluxConstants.MEMBERSHIP_CANCEL_MEMBERSHIP;
            case OWNER -> FluxConstants.MEMBERSHIP_TRANSFER_OWNERSHIP;
        };
        int response = network.changeMembership(actingPlayer(server, network, actor), target, type);
        if (response != FluxConstants.RESPONSE_SUCCESS) {
            return switch (response) {
                case FluxConstants.RESPONSE_NO_OWNER, FluxConstants.RESPONSE_NO_ADMIN -> ActionResult.error(Status.DENIED,
                        "Only the owner can make admins or hand over the network.");
                default -> invalid("Flux does not allow this for " + targetName + ".");
            };
        }
        var changes = new ArrayList<Change>();
        changes.add(new Change(FluxMapping.member(networkId, target), "access", before, memberAccess(network, target)));
        if (edit.role() == FluxActions.Role.OWNER) {
            changes.add(new Change(FluxMapping.network(networkId), "owner", null, target.toString()));
        }
        return ActionResult.ok(changes);
    }

    /**
     * Adds a member through Flux's own member list format. Flux re-reads its password from the
     * same tag, so it is carried over.
     */
    private static void addOfflineMember(FluxNetwork network, UUID player, String name) {
        var all = new CompoundTag();
        network.writeCustomTag(all, FluxConstants.NBT_SAVE_ALL);
        var tag = new CompoundTag();
        network.writeCustomTag(tag, FluxConstants.NBT_NET_MEMBERS);
        ListTag members = tag.getList("members", Tag.TAG_COMPOUND);
        var member = new CompoundTag();
        member.putUUID("playerUUID", player);
        member.putString("cachedName", name);
        member.putByte("accessLevel", AccessLevel.USER.getKey());
        members.add(member);
        tag.put("members", members);
        if (all.contains("password")) tag.putString("password", all.getString("password"));
        network.readCustomTag(tag, FluxConstants.NBT_NET_MEMBERS);
    }

    /** Flux's member calls need a player: the actor, or for operators the network's owner. */
    private static Player actingPlayer(MinecraftServer server, FluxNetwork network, Actor actor) {
        UUID uuid = actor.operator() ? network.getOwnerUUID() : actor.player();
        String name = uuid.equals(actor.player()) ? actor.name() : ownerName(network);
        return FakePlayerFactory.get(server.overworld(), new GameProfile(uuid, name));
    }

    private static String ownerName(FluxNetwork network) {
        NetworkMember owner = network.getMemberByUUID(network.getOwnerUUID());
        return owner == null || owner.getCachedName() == null ? "owner" : owner.getCachedName();
    }

    private static Optional<GameProfile> profile(MinecraftServer server, FluxNetwork network, UUID player,
            String fallbackName) {
        NetworkMember member = network.getMemberByUUID(player);
        if (member != null) return Optional.of(new GameProfile(player, member.getCachedName()));
        var online = server.getPlayerList().getPlayer(player);
        if (online != null) return Optional.of(online.getGameProfile());
        var cache = server.getProfileCache();
        Optional<GameProfile> cached = cache == null ? Optional.empty() : cache.get(player);
        if (cached.isPresent() || fallbackName == null) return cached;
        return Optional.of(new GameProfile(player, fallbackName));
    }

    private static Optional<GameProfile> profileByName(MinecraftServer server, FluxNetwork network, String name) {
        for (NetworkMember member : network.getAllMembers()) {
            if (name.equalsIgnoreCase(member.getCachedName())) {
                return Optional.of(new GameProfile(member.getPlayerUUID(), member.getCachedName()));
            }
        }
        var online = server.getPlayerList().getPlayerByName(name);
        if (online != null) return Optional.of(online.getGameProfile());
        var cache = server.getProfileCache();
        return cache == null ? Optional.empty() : cache.get(name);
    }

    private static String memberAccess(FluxNetwork network, UUID player) {
        NetworkMember m = network.getMemberByUUID(player);
        return m == null ? null : lower(m.getAccessLevel().name());
    }

    // --- helpers ---------------------------------------------------------------------------

    /** Flux's access rule on live state; operators act as owner. */
    static AccessLevel access(FluxNetwork network, Actor actor) {
        if (actor.operator()) return AccessLevel.OWNER;
        if (actor.player().equals(network.getOwnerUUID())) return AccessLevel.OWNER;
        NetworkMember member = network.getMemberByUUID(actor.player());
        if (member != null) return member.getAccessLevel();
        return network.getSecurityLevel() == SecurityLevel.PUBLIC ? AccessLevel.USER : AccessLevel.BLOCKED;
    }

    /** Tells clients about the change, as Flux's own edit packets do. */
    private static void broadcast(FluxNetwork network, byte what) {
        try {
            Channel.get().sendToAll(Messages.updateNetwork(network, what));
        } catch (RuntimeException e) {
            LOG.warn("Overseer could not send the Flux network update to clients", e);
        }
    }

    private static ResourceKey<net.minecraft.world.level.Level> dimension(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null) throw new FluxActions.Invalid("Unknown dimension: " + id);
        return ResourceKey.create(Registries.DIMENSION, location);
    }

    private static GlobalPos globalPos(DevicePos pos) {
        return GlobalPos.of(dimension(pos.dimension()), new BlockPos(pos.x(), pos.y(), pos.z()));
    }

    private static ResourceRef deviceRef(DevicePos pos) {
        return new ResourceRef(FluxMapping.PROVIDER, "device", pos.id());
    }

    private static String color(int rgb) {
        return String.format(Locale.ROOT, "#%06x", rgb & 0xFFFFFF);
    }

    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static ActionResult notFound() {
        return ActionResult.error(Status.NOT_FOUND, "The network no longer exists.");
    }

    private static ActionResult denied() {
        return ActionResult.error(Status.DENIED, "Only the network's owner and admins may change it.");
    }

    private static ActionResult invalid(String message) {
        return ActionResult.error(Status.INVALID, message);
    }
}
