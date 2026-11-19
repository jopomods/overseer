package io.github.jopojp.overseer.forge.integration.ae2;

import appeng.blockentity.AEBaseBlockEntity;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.parts.AEBasePart;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What AE2 has but does not offer as API (ADR-0017), looked up once by reflection. Every lookup may
 * fail on another AE2 build; the feature is then missing, never the integration.
 *
 * <ul>
 *   <li>Job suspension: {@code CraftingCpuLogic#isJobSuspended()/setJobSuspended(boolean)}. Some
 *       AE2 builds have it (the Star Technology fork {@code 15.5.4-uelm}); AE2 15.4.12 does not.
 *   <li>Custom names of blocks and parts: the private field {@code customName} of
 *       {@code AEBaseBlockEntity} and {@code AEBasePart}. AE2 sets it from anvil-named items.
 * </ul>
 */
final class Ae2Compat {
    private static final Logger LOG = LoggerFactory.getLogger(Ae2Compat.class);

    private static final MethodHandle IS_SUSPENDED;
    private static final MethodHandle SET_SUSPENDED;
    private static final Field BLOCK_NAME = field(AEBaseBlockEntity.class, "customName");
    private static final Field PART_NAME = field(AEBasePart.class, "customName");

    static {
        MethodHandle is = null;
        MethodHandle set = null;
        try {
            var lookup = MethodHandles.publicLookup();
            is = lookup.findVirtual(CraftingCpuLogic.class, "isJobSuspended", MethodType.methodType(boolean.class));
            set = lookup.findVirtual(CraftingCpuLogic.class, "setJobSuspended",
                    MethodType.methodType(void.class, boolean.class));
            LOG.info("Overseer: this AE2 build can suspend crafting jobs");
        } catch (ReflectiveOperationException | RuntimeException e) {
            is = null;
            set = null;
        }
        IS_SUSPENDED = is;
        SET_SUSPENDED = set;
    }

    private Ae2Compat() {}

    /** Whether this AE2 build can suspend and resume crafting jobs. */
    static boolean canSuspend() {
        return SET_SUSPENDED != null && IS_SUSPENDED != null;
    }

    /** {@code null} when this AE2 build cannot suspend jobs. */
    static Boolean suspended(CraftingCpuLogic logic) {
        if (IS_SUSPENDED == null) return null;
        try {
            return (boolean) IS_SUSPENDED.invoke(logic);
        } catch (Throwable e) {
            return null;
        }
    }

    static void setSuspended(CraftingCpuLogic logic, boolean suspended) {
        if (SET_SUSPENDED == null) throw new IllegalStateException("This AE2 build cannot suspend jobs");
        try {
            SET_SUSPENDED.invoke(logic, suspended);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    /** Whether names of blocks and parts can be set. */
    static boolean canName() {
        return BLOCK_NAME != null && PART_NAME != null;
    }

    /** Sets or (with {@code null}) removes the custom name of an AE2 block entity. Saves nothing. */
    static void setName(AEBaseBlockEntity blockEntity, Component name) {
        set(BLOCK_NAME, blockEntity, name);
    }

    /** Sets or (with {@code null}) removes the custom name of an AE2 part. Saves nothing. */
    static void setName(AEBasePart part, Component name) {
        set(PART_NAME, part, name);
    }

    private static void set(Field field, Object target, Component name) {
        if (field == null) throw new IllegalStateException("This AE2 build hides custom names");
        try {
            field.set(target, name);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warn("Overseer: AE2 field {}.{} not found; names cannot be changed from the dashboard",
                    owner.getSimpleName(), name);
            return null;
        }
    }
}
