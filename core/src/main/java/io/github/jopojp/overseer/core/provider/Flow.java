package io.github.jopojp.overseer.core.provider;

import java.util.Objects;

/**
 * What one device moved into or out of a storage network in one collection (ADR-0020), e.g. an
 * AE2 import bus inserting ore or a player taking ingots from a terminal.
 *
 * @param device     stable id of the device within its network: a position ({@code dimension@x,y,z},
 *                   plus {@code /<side>} for parts), {@code player:<uuid>} for players, or
 *                   {@code other} for anything the provider cannot place
 * @param deviceKind what the device is, e.g. {@code import_bus}, {@code export_bus}, {@code player},
 *                   {@code crafting}; for grouping and labels
 * @param deviceName display name of the device (custom name, player name, or the item's name)
 * @param item       what moved; its {@code amount} is ignored
 * @param inserted   amount put into the network (items, or mB for fluids)
 * @param extracted  amount taken out of the network
 */
public record Flow(String device, String deviceKind, String deviceName, Stack item, long inserted, long extracted) {
    public Flow {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(deviceKind, "deviceKind");
        Objects.requireNonNull(item, "item");
        if (deviceName == null) deviceName = device;
        if (inserted < 0 || extracted < 0) throw new IllegalArgumentException("amounts must not be negative");
    }
}
