package io.github.jopojp.overseer.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.jopojp.overseer.core.provider.ResourceRef;
import org.junit.jupiter.api.Test;

class ResourceRefTest {
    @Test
    void keyJoinsParts() {
        var ref = new ResourceRef("flux", "device", "minecraft:overworld@12,64,-3");
        assertEquals("flux/device/minecraft:overworld@12,64,-3", ref.key());
    }

    @Test
    void rejectsInvalidProvider() {
        assertThrows(IllegalArgumentException.class, () -> new ResourceRef("Flux Networks", "device", "x"));
    }

    @Test
    void rejectsBlankId() {
        assertThrows(IllegalArgumentException.class, () -> new ResourceRef("flux", "device", " "));
    }
}
