package io.github.jopojp.overseer.forge.integration.ae2;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The world actions AE2 grids offer (ADR-0016, ADR-0017) and their parameters, parsed and
 * validated. Pure: no AE2 or Minecraft classes, so every rule is unit-tested. {@link Ae2Writer}
 * applies them. Parameters are documented in {@code docs/modules/ae2.md}.
 *
 * <p>Every action targets a grid ({@code ae2/grid/<id>}); the part it changes is a parameter.
 */
public final class Ae2Actions {
    public static final String CPU_CANCEL = "ae2.cpu.cancel";
    public static final String CPU_SUSPEND = "ae2.cpu.suspend";
    public static final String CPU_EDIT = "ae2.cpu.edit";
    public static final String DRIVE_EDIT = "ae2.drive.edit";
    public static final String PROVIDER_EDIT = "ae2.provider.edit";

    /** Actions every AE2 build supports. */
    public static final Set<String> BASE_TYPES = Set.of(CPU_CANCEL, CPU_EDIT, DRIVE_EDIT, PROVIDER_EDIT);

    /** Minecraft's anvil limit; AE2 names blocks and parts like an anvil does. */
    public static final int MAX_NAME = 50;

    /** AE2 CPU selection modes, lower case as in the snapshot. */
    public enum Mode { ANY, PLAYER_ONLY, MACHINE_ONLY }

    /** AE2 lock crafting modes, lower case as in the snapshot. */
    public enum Lock { NONE, LOCK_UNTIL_PULSE, LOCK_WHILE_HIGH, LOCK_WHILE_LOW, LOCK_UNTIL_RESULT }

    private static final Pattern PROVIDER_ID = Pattern.compile(
            "[a-z0-9_.-]+:[a-z0-9_./-]+@-?\\d+,-?\\d+,-?\\d+(/(down|up|north|south|west|east))?");
    private static final Pattern ITEM_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern POS_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+@-?\\d+,-?\\d+,-?\\d+");
    /** CPUs that are no multiblock (add-ons) are numbered within their grid. */
    private static final Pattern INDEX_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+@-?\\d+,-?\\d+,-?\\d+#\\d+");

    private Ae2Actions() {}

    /** A parameter is wrong; the message is shown to the user. */
    public static final class Invalid extends RuntimeException {
        public Invalid(String message) {
            super(message, null, false, false);
        }
    }

    /** {@code null} fields stay as they are; an empty name removes the custom name. */
    public record CpuEdit(String cpu, String name, Mode mode) {}

    public record CpuSuspend(String cpu, boolean suspended) {}

    public record DriveEdit(String drive, int priority) {}

    /**
     * Settings of a pattern provider; {@code null} fields stay as they are. An empty name or icon
     * removes it.
     */
    public record ProviderEdit(String provider, String name, Integer priority, Boolean blocking, Lock lock,
            Boolean visible, String icon) {}

    /** The grid id of the target. */
    public static String gridId(ResourceRef target) {
        if (!target.provider().equals(Ae2Mapping.PROVIDER) || !target.type().equals("grid")) {
            throw new Invalid("AE2 actions target a grid.");
        }
        if (!POS_ID.matcher(target.id()).matches()) throw new Invalid("Unknown grid.");
        return target.id();
    }

    /** {@code cpu}. */
    public static String cpu(JsonObject params) {
        String cpu = optString(params, "cpu");
        if (cpu == null) throw new Invalid("cpu is required.");
        if (!POS_ID.matcher(cpu).matches() && !INDEX_ID.matcher(cpu).matches()) throw new Invalid("Unknown CPU: " + cpu);
        return cpu;
    }

    /** {@code cpu}, {@code suspended}. */
    public static CpuSuspend cpuSuspend(JsonObject params) {
        Boolean suspended = optBool(params, "suspended");
        if (suspended == null) throw new Invalid("suspended is required.");
        return new CpuSuspend(cpu(params), suspended);
    }

    /** {@code cpu}, {@code name?}, {@code mode?} ({@code any}, {@code player_only}, {@code machine_only}). */
    public static CpuEdit cpuEdit(JsonObject params) {
        String cpu = cpu(params);
        String name = optString(params, "name");
        if (name != null) name = name(name);
        Mode mode = null;
        String modeText = optString(params, "mode");
        if (modeText != null) {
            try {
                mode = Mode.valueOf(modeText.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new Invalid("Mode must be any, player_only or machine_only.");
            }
        }
        if (name == null && mode == null) throw new Invalid("Nothing to change.");
        return new CpuEdit(cpu, name, mode);
    }

    /** {@code drive}, {@code priority}. */
    public static DriveEdit driveEdit(JsonObject params) {
        String drive = optString(params, "drive");
        if (drive == null) throw new Invalid("drive is required.");
        if (!POS_ID.matcher(drive).matches()) throw new Invalid("Unknown drive: " + drive);
        Long priority = optLong(params, "priority");
        if (priority == null) throw new Invalid("priority is required.");
        if (priority < Integer.MIN_VALUE || priority > Integer.MAX_VALUE) throw new Invalid("priority is out of range.");
        return new DriveEdit(drive, priority.intValue());
    }

    /**
     * {@code provider}, {@code name?}, {@code priority?}, {@code blocking?}, {@code lock?},
     * {@code visible?}, {@code icon?} (item registry id, empty removes it).
     */
    public static ProviderEdit providerEdit(JsonObject params) {
        String provider = optString(params, "provider");
        if (provider == null) throw new Invalid("provider is required.");
        if (!PROVIDER_ID.matcher(provider).matches()) throw new Invalid("Unknown pattern provider: " + provider);
        String name = optString(params, "name");
        if (name != null) name = name(name);
        Long priority = optLong(params, "priority");
        if (priority != null && (priority < Integer.MIN_VALUE || priority > Integer.MAX_VALUE)) {
            throw new Invalid("priority is out of range.");
        }
        Lock lock = null;
        String lockText = optString(params, "lock");
        if (lockText != null) {
            try {
                lock = Lock.valueOf(lockText.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new Invalid("Unknown lock mode: " + lockText);
            }
        }
        String icon = optString(params, "icon");
        if (icon != null) {
            icon = icon.strip();
            if (!icon.isEmpty() && !ITEM_ID.matcher(icon).matches()) throw new Invalid("icon must be an item id like minecraft:diamond.");
        }
        var edit = new ProviderEdit(provider, name, priority == null ? null : priority.intValue(), optBool(params, "blocking"),
                lock, optBool(params, "visible"), icon);
        if (name == null && priority == null && edit.blocking() == null && lock == null && edit.visible() == null
                && icon == null) {
            throw new Invalid("Nothing to change.");
        }
        return edit;
    }

    /**
     * Names as an anvil allows them: at most 50 characters, colour codes ({@code §}) included,
     * no control characters. Surrounding spaces are removed; empty means "no custom name".
     */
    static String name(String name) {
        String stripped = name.strip();
        if (stripped.length() > MAX_NAME) throw new Invalid("Names have at most " + MAX_NAME + " characters.");
        if (stripped.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) throw new Invalid("The name contains invalid characters.");
        return stripped;
    }

    /** {@code dimension@x,y,z} of a part id, without a {@code #index} suffix. */
    public static boolean isPosition(String id) {
        return POS_ID.matcher(id).matches();
    }

    private static JsonPrimitive primitive(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return null;
        if (!e.isJsonPrimitive()) throw new Invalid(key + " has the wrong type.");
        return e.getAsJsonPrimitive();
    }

    static String optString(JsonObject o, String key) {
        JsonPrimitive p = primitive(o, key);
        if (p == null) return null;
        if (!p.isString()) throw new Invalid(key + " must be text.");
        return p.getAsString();
    }

    static Boolean optBool(JsonObject o, String key) {
        JsonPrimitive p = primitive(o, key);
        if (p == null) return null;
        if (!p.isBoolean()) throw new Invalid(key + " must be true or false.");
        return p.getAsBoolean();
    }

    static Long optLong(JsonObject o, String key) {
        JsonPrimitive p = primitive(o, key);
        if (p == null) return null;
        if (!p.isNumber()) throw new Invalid(key + " must be a number.");
        try {
            return p.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException e) {
            throw new Invalid(key + " must be a whole number.");
        }
    }
}
