package io.github.jopojp.overseer.forge.integration.ae2;

import net.minecraft.ChatFormatting;

/** Display names as the dashboard shows them. */
public final class Ae2Names {
    private Ae2Names() {}

    /**
     * Without legacy formatting codes: modpacks name items like {@code §5EV Energy Hatch}; the
     * game colours them, the dashboard would show the codes.
     */
    public static String plain(String text) {
        String stripped = ChatFormatting.stripFormatting(text);
        return stripped == null ? "" : stripped.strip();
    }
}
