package io.github.jopojp.overseer.forge.integration.flux;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.forge.integration.flux.FluxModel.Security;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The world actions Flux Networks offers (ADR-0016) and their parameters, parsed and validated.
 * Pure: no Flux or Minecraft classes, so every rule is unit-tested. {@link FluxWriter} applies
 * the parsed requests. Parameters are documented in {@code docs/modules/flux.md}.
 *
 * <p>Every action targets a network ({@code flux/network/<id>}); permissions are checked against
 * it (owners and admins, ADR-0016).
 */
public final class FluxActions {
    public static final String NETWORK_EDIT = "flux.network.edit";
    public static final String DEVICE_EDIT = "flux.device.edit";
    public static final String DEVICE_CONNECT = "flux.device.connect";
    public static final String MEMBER_EDIT = "flux.member.edit";
    public static final Set<String> TYPES = Set.of(NETWORK_EDIT, DEVICE_EDIT, DEVICE_CONNECT, MEMBER_EDIT);

    /** Flux's limits: names and passwords are cut off or refused beyond these in game. */
    public static final int MAX_NAME = 24;
    public static final int MAX_PASSWORD = 16;
    public static final int MIN_PRIORITY = -9999;
    public static final int MAX_PRIORITY = 9999;
    /** Devices one action may change. */
    public static final int MAX_DEVICES = 512;

    private static final Pattern COLOR = Pattern.compile("#?([0-9a-fA-F]{6})");
    private static final Pattern DEVICE_ID = Pattern.compile("([a-z0-9_.-]+:[a-z0-9_./-]+)@(-?\\d+),(-?\\d+),(-?\\d+)");
    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private FluxActions() {}

    /** A parameter is wrong; the message is shown to the user. */
    public static final class Invalid extends RuntimeException {
        public Invalid(String message) {
            super(message, null, false, false);
        }
    }

    /** {@code null} fields stay as they are. */
    public record NetworkEdit(String name, Integer color, Security security, String password) {
        public boolean isEmpty() {
            return name == null && color == null && security == null && password == null;
        }
    }

    public record DevicePos(String dimension, int x, int y, int z) {
        public String id() {
            return dimension + "@" + x + "," + y + "," + z;
        }
    }

    /** Settings of one device; {@code null} fields stay as they are. */
    public record DeviceEdit(DevicePos pos, String name, Integer priority, Long limit, Boolean disableLimit,
            Boolean surge, Boolean chunkLoading) {
        public boolean isEmpty() {
            return name == null && priority == null && limit == null && disableLimit == null && surge == null
                    && chunkLoading == null;
        }
    }

    public enum Role { USER, ADMIN, OWNER, REMOVE }

    /** {@code player} or {@code name} is set; with both, the name is only shown if the server does not know it. */
    public record MemberEdit(UUID player, String name, Role role) {}

    public static int networkId(ResourceRef target) {
        if (!target.provider().equals(FluxMapping.PROVIDER) || !target.type().equals("network")) {
            throw new Invalid("Flux actions target a network.");
        }
        try {
            return Integer.parseInt(target.id());
        } catch (NumberFormatException e) {
            throw new Invalid("Unknown network.");
        }
    }

    /** {@code name}, {@code color} ({@code #rrggbb}), {@code security}, {@code password}. */
    public static NetworkEdit networkEdit(JsonObject params) {
        String name = optString(params, "name");
        if (name != null) name = name(name, false);
        Integer color = null;
        String colorText = optString(params, "color");
        if (colorText != null) {
            var m = COLOR.matcher(colorText.strip());
            if (!m.matches()) throw new Invalid("Colour must be a hex code like #ff7a00.");
            color = Integer.parseInt(m.group(1), 16);
        }
        Security security = null;
        String securityText = optString(params, "security");
        if (securityText != null) {
            try {
                security = Security.valueOf(securityText.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new Invalid("Security must be public, encrypted or private.");
            }
        }
        String password = optString(params, "password");
        if (password != null) {
            if (password.isEmpty() || password.length() > MAX_PASSWORD) {
                throw new Invalid("Passwords have 1 to " + MAX_PASSWORD + " characters.");
            }
            if (password.chars().anyMatch(c -> c < 0x20)) throw new Invalid("The password contains invalid characters.");
        }
        var edit = new NetworkEdit(name, color, security, password);
        if (edit.isEmpty()) throw new Invalid("Nothing to change.");
        return edit;
    }

    /**
     * {@code devices}: list of {@code {id, name?, priority?, limit?, disableLimit?, surge?, chunkLoading?}}.
     * Each device at most once.
     */
    public static List<DeviceEdit> deviceEdits(JsonObject params) {
        JsonArray devices = array(params, "devices");
        var result = new ArrayList<DeviceEdit>();
        var seen = new HashSet<String>();
        for (JsonElement element : devices) {
            if (!element.isJsonObject()) throw new Invalid("Each device must be an object with an id.");
            JsonObject d = element.getAsJsonObject();
            DevicePos pos = devicePos(optString(d, "id"));
            if (!seen.add(pos.id())) throw new Invalid("A device is listed twice.");
            String name = optString(d, "name");
            if (name != null) name = name(name, true);
            Integer priority = optInt(d, "priority");
            if (priority != null && (priority < MIN_PRIORITY || priority > MAX_PRIORITY)) {
                throw new Invalid("Priority must be between " + MIN_PRIORITY + " and " + MAX_PRIORITY + ".");
            }
            Long limit = optLong(d, "limit");
            if (limit != null && limit < 0) throw new Invalid("The limit cannot be negative.");
            var edit = new DeviceEdit(pos, name, priority, limit, optBool(d, "disableLimit"), optBool(d, "surge"),
                    optBool(d, "chunkLoading"));
            if (edit.isEmpty()) throw new Invalid("Nothing to change for " + pos.id() + ".");
            result.add(edit);
        }
        return result;
    }

    /** {@code devices}: list of device ids to connect to the target network. */
    public static List<DevicePos> connect(JsonObject params) {
        var result = new ArrayList<DevicePos>();
        var seen = new HashSet<String>();
        for (JsonElement element : array(params, "devices")) {
            if (!element.isJsonPrimitive()) throw new Invalid("Devices are listed by id.");
            DevicePos pos = devicePos(element.getAsString());
            if (seen.add(pos.id())) result.add(pos);
        }
        return result;
    }

    /**
     * {@code player} (UUID) or {@code name}, and {@code role}: user, admin, owner or remove. With a
     * UUID, {@code name} may be given too: it names a player the server's profile cache does not know.
     */
    public static MemberEdit memberEdit(JsonObject params) {
        String role = optString(params, "role");
        if (role == null) throw new Invalid("role is required.");
        Role parsed;
        try {
            parsed = Role.valueOf(role.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new Invalid("Role must be user, admin, owner or remove.");
        }
        String player = optString(params, "player");
        String name = optString(params, "name");
        if (player == null && name == null) throw new Invalid("Give the player's UUID or name.");
        if (name != null) {
            name = name.strip();
            if (!PLAYER_NAME.matcher(name).matches()) throw new Invalid("That is not a Minecraft player name.");
        }
        UUID uuid = null;
        if (player != null) {
            try {
                uuid = UUID.fromString(player.strip());
            } catch (IllegalArgumentException e) {
                throw new Invalid("player must be a UUID.");
            }
        }
        return new MemberEdit(uuid, name, parsed);
    }

    public static DevicePos devicePos(String id) {
        if (id == null) throw new Invalid("Device id is required.");
        var m = DEVICE_ID.matcher(id);
        if (!m.matches()) throw new Invalid("Unknown device id: " + id);
        try {
            return new DevicePos(m.group(1), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)));
        } catch (NumberFormatException e) {
            throw new Invalid("Unknown device id: " + id);
        }
    }

    /**
     * Flux's name rule: at most 24 characters, colour codes included. Network names must not be
     * empty; an empty device name shows the device type in game.
     */
    static String name(String name, boolean mayBeEmpty) {
        if (name.length() > MAX_NAME) throw new Invalid("Names have at most " + MAX_NAME + " characters.");
        if (!mayBeEmpty && name.isBlank()) throw new Invalid("The name cannot be empty.");
        if (name.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) throw new Invalid("The name contains invalid characters.");
        return name;
    }

    private static JsonArray array(JsonObject params, String key) {
        JsonElement e = params.get(key);
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().isEmpty()) throw new Invalid(key + " is required.");
        if (e.getAsJsonArray().size() > MAX_DEVICES) throw new Invalid("At most " + MAX_DEVICES + " devices at once.");
        return e.getAsJsonArray();
    }

    private static JsonPrimitive primitive(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return null;
        if (!e.isJsonPrimitive()) throw new Invalid(key + " has the wrong type.");
        return e.getAsJsonPrimitive();
    }

    private static String optString(JsonObject o, String key) {
        JsonPrimitive p = primitive(o, key);
        if (p == null) return null;
        if (!p.isString()) throw new Invalid(key + " must be text.");
        return p.getAsString();
    }

    private static Boolean optBool(JsonObject o, String key) {
        JsonPrimitive p = primitive(o, key);
        if (p == null) return null;
        if (!p.isBoolean()) throw new Invalid(key + " must be true or false.");
        return p.getAsBoolean();
    }

    private static Long optLong(JsonObject o, String key) {
        JsonPrimitive p = primitive(o, key);
        if (p == null) return null;
        if (!p.isNumber()) throw new Invalid(key + " must be a number.");
        try {
            return p.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException e) {
            throw new Invalid(key + " must be a whole number.");
        }
    }

    private static Integer optInt(JsonObject o, String key) {
        Long value = optLong(o, key);
        if (value == null) return null;
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw new Invalid(key + " is out of range.");
        return value.intValue();
    }
}
