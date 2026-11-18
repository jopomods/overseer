package io.github.jopojp.overseer.core.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class IconStoreTest {
    @TempDir
    Path dir;

    public static byte[] png(int marker) {
        byte[] bytes = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, (byte) marker};
        return bytes;
    }

    @Test
    void exactKeyThenBaseKeyThenFallback() throws Exception {
        var calls = new AtomicInteger();
        try (var db = Database.open(dir.resolve("overseer.db"));
                var icons = new IconStore(db.openControlWriter(), key -> {
                    calls.incrementAndGet();
                    return key.equals("item:mod:gear") ? png(9) : null;
                })) {
            int stored = icons.putAll(Map.of(
                    "item:minecraft:book", png(1),
                    "item:minecraft:book#ab12", png(2),
                    "item:minecraft:bad", new byte[] {1, 2, 3},
                    "item:minecraft:huge", Arrays.copyOf(png(3), IconStore.MAX_PNG_BYTES + 1)), null, Instant.EPOCH);
            assertEquals(2, stored, "only PNGs within the size limit");
            assertArrayEquals(png(2), icons.find("item:minecraft:book#ab12").orElseThrow());
            assertArrayEquals(png(1), icons.find("item:minecraft:book#ffff").orElseThrow(), "NBT variant -> base");
            assertArrayEquals(png(9), icons.find("item:mod:gear#1").orElseThrow(), "fallback on the base key");
            assertFalse(icons.find("item:minecraft:bad").isPresent());
            int before = calls.get();
            icons.find("item:minecraft:bad");
            assertEquals(before, calls.get(), "misses are cached");
            assertEquals(2, icons.count());
        }
    }

    @Test
    void uploadsReplaceAndClearTheCache() throws Exception {
        try (var db = Database.open(dir.resolve("overseer.db"));
                var icons = new IconStore(db.openControlWriter(), null)) {
            assertTrue(icons.find("item:minecraft:book").isEmpty());
            icons.putAll(Map.of("item:minecraft:book", png(1)), null, Instant.EPOCH);
            assertArrayEquals(png(1), icons.find("item:minecraft:book").orElseThrow());
            icons.putAll(Map.of("item:minecraft:book", png(5)), null, Instant.EPOCH);
            assertArrayEquals(png(5), icons.find("item:minecraft:book").orElseThrow());
        }
    }
}
