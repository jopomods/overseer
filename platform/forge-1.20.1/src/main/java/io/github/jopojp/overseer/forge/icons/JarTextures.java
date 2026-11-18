package io.github.jopojp.overseer.forge.icons;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraftforge.fml.ModList;

/**
 * Icon fallback without a client export: the item's own texture from its mod jar
 * ({@code assets/<ns>/textures/item/<path>.png}, then {@code textures/block/<path>.png}). Works for
 * items with a plain texture. Vanilla items have none on a dedicated server (its jar has no
 * textures), and tinted or layered models (e.g. GregTech materials) render differently: those need
 * {@code /ovicons export}. Animated textures come as a vertical strip; the UI shows the first frame.
 */
public final class JarTextures {
    private JarTextures() {}

    /** @param baseKey stack key without NBT hash, e.g. {@code item:ae2:certus_quartz_crystal} */
    public static byte[] find(String baseKey) {
        if (!baseKey.startsWith("item:")) return null;
        String id = baseKey.substring("item:".length());
        int colon = id.indexOf(':');
        if (colon < 0) return null;
        String namespace = id.substring(0, colon);
        String path = id.substring(colon + 1);
        if (!namespace.matches("[a-z0-9_.-]+") || !path.matches("[a-z0-9_./-]+") || path.contains("..")) return null;
        var modFile = ModList.get().getModFileById(namespace);
        if (modFile == null) return null;
        for (String folder : new String[] {"item", "block"}) {
            try {
                Path file = modFile.getFile().findResource("assets", namespace, "textures", folder, path + ".png");
                if (Files.isRegularFile(file)) return Files.readAllBytes(file);
            } catch (Exception e) {
                // unreadable or not there: try the next folder
            }
        }
        return null;
    }
}
