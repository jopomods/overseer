package io.github.jopojp.overseer.forge;

import io.github.jopojp.overseer.core.http.HttpConfig;
import java.util.HashSet;
import java.util.List;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * {@code serverconfig/overseer-server.toml} in the world folder. Only what must be known
 * before the web server starts lives here; everything else is configured in the UI
 * (ADR-0007).
 */
public final class OverseerConfig {
    public static final ForgeConfigSpec SPEC;

    static final ForgeConfigSpec.BooleanValue ENABLED;
    static final ForgeConfigSpec.ConfigValue<String> BIND_ADDRESS;
    static final ForgeConfigSpec.IntValue PORT;
    static final ForgeConfigSpec.ConfigValue<List<? extends String>> TRUSTED_PROXIES;
    static final ForgeConfigSpec.BooleanValue FORCE_SECURE_COOKIES;
    static final ForgeConfigSpec.ConfigValue<String> PUBLIC_URL;
    static final ForgeConfigSpec.BooleanValue AE2_PLACER_ACCESS;
    static final ForgeConfigSpec.BooleanValue AE2_CRAFTING;
    static final ForgeConfigSpec.BooleanValue AE2_RESTOCK;
    static final ForgeConfigSpec.BooleanValue AE2_FLOW;
    static final ForgeConfigSpec.EnumValue<EnergyTiers> ENERGY_TIERS;

    /** Voltage tiers in device names (docs/modules/flux.md). */
    public enum EnergyTiers {
        /** GregTech tiers when a GregTech mod is installed, otherwise none. */
        AUTO,
        /** Always GregTech tiers (ULV ... MAX). */
        GREGTECH,
        /** No tiers. */
        OFF
    }

    static {
        var builder = new ForgeConfigSpec.Builder();
        builder.comment("Overseer web dashboard").push("web");
        ENABLED = builder.comment("Start the web dashboard with the server.")
                .define("enabled", true);
        BIND_ADDRESS = builder.comment(
                        "Network interface to listen on. Keep 127.0.0.1 and put a reverse proxy with TLS in front",
                        "(see docs/guides/reverse-proxy.md). Use 0.0.0.0 only on a trusted LAN.")
                .define("bindAddress", HttpConfig.DEFAULTS.bindAddress());
        PORT = builder.comment("TCP port of the dashboard.")
                .defineInRange("port", HttpConfig.DEFAULTS.port(), 1, 65535);
        TRUSTED_PROXIES = builder.comment(
                        "IP addresses of reverse proxies whose X-Forwarded-For/-Proto/-Host headers are trusted.")
                .defineListAllowEmpty(List.of("trustedProxies"), () -> List.copyOf(HttpConfig.DEFAULTS.trustedProxies()),
                        o -> o instanceof String s && !s.isBlank());
        FORCE_SECURE_COOKIES = builder.comment(
                        "Always mark cookies Secure. Enable if TLS is terminated elsewhere without X-Forwarded-Proto.")
                .define("forceSecureCookies", false);
        PUBLIC_URL = builder.comment(
                        "Address players open in the browser, e.g. https://dash.example.org.",
                        "Used for links printed by /ov. Empty = http://<bindAddress>:<port>.")
                .define("publicUrl", "");
        builder.pop();
        builder.comment("Who sees what, on top of the mods' own rules").push("access");
        AE2_PLACER_ACCESS = builder.comment(
                        "AE2: players who placed any block or part of an ME network may view it (not edit it).",
                        "Off by default: anyone could place a cable on a neighbour's network and watch it.")
                .define("ae2PlacerAccess", false);
        builder.pop();
        builder.comment("What players may change from the dashboard, on top of the mods' own rules").push("actions");
        AE2_CRAFTING = builder.comment(
                        "AE2: owners of an ME network (and their FTB Teams party) may plan and start crafting jobs",
                        "from the dashboard, like from a terminal anywhere. Results go into the network.")
                .define("ae2Crafting", true);
        AE2_RESTOCK = builder.comment(
                        "AE2: owners may let their network craft a watched item whenever its stock falls below the",
                        "target (ADR-0021). Jobs run as the player who switched it on. Needs ae2Crafting.")
                .define("ae2Restock", true);
        builder.pop();
        builder.comment("Applied Energistics 2").push("ae2");
        AE2_FLOW = builder.comment(
                        "Count what every bus, interface, CPU and player moves into and out of ME networks (ADR-0020).",
                        "Stored per minute. Costs a little on AE2's busiest path; switch off to stop at once.")
                .define("flow", true);
        builder.pop();
        builder.comment("How the dashboard shows things").push("dashboard");
        ENERGY_TIERS = builder.comment(
                        "Voltage tiers at the end of Flux device names (\"Factory: Assembler MV\"): coloured, filterable.",
                        "AUTO = GregTech tiers when GregTech (gtceu) is installed; GREGTECH = always; OFF = never.")
                .defineEnum("energyTiers", EnergyTiers.AUTO);
        builder.pop();
        SPEC = builder.build();
    }

    private OverseerConfig() {}

    static HttpConfig http() {
        return new HttpConfig(BIND_ADDRESS.get(), PORT.get(), new HashSet<>(TRUSTED_PROXIES.get()),
                FORCE_SECURE_COOKIES.get(), HttpConfig.DEFAULTS.maxRequestBytes());
    }

    /** Read every collection, so a change applies without a restart. */
    public static boolean ae2PlacerAccess() {
        return SPEC.isLoaded() && AE2_PLACER_ACCESS.get();
    }

    /** Read on every request, so a change applies without a restart. */
    public static boolean ae2Crafting() {
        return !SPEC.isLoaded() || AE2_CRAFTING.get();
    }

    /** Read every collection and request, so a change applies without a restart. */
    public static boolean ae2Restock() {
        return !SPEC.isLoaded() || AE2_RESTOCK.get();
    }

    /** Read every collection, so a change applies without a restart. */
    public static boolean ae2Flow() {
        return !SPEC.isLoaded() || AE2_FLOW.get();
    }

    /** The tier scheme for the dashboard: {@code gregtech} or {@code none}. Read every collection. */
    public static String energyTiers() {
        EnergyTiers mode = SPEC.isLoaded() ? ENERGY_TIERS.get() : EnergyTiers.AUTO;
        boolean gregtech = switch (mode) {
            case GREGTECH -> true;
            case OFF -> false;
            case AUTO -> net.minecraftforge.fml.ModList.get().isLoaded("gtceu");
        };
        return gregtech ? "gregtech" : "none";
    }

    public static String publicUrl() {
        String configured = PUBLIC_URL.get().strip();
        if (!configured.isEmpty()) return configured.replaceAll("/+$", "");
        return "http://" + BIND_ADDRESS.get() + ":" + PORT.get();
    }
}
