package io.github.jopojp.overseer.forge;

import com.mojang.logging.LogUtils;
import io.github.jopojp.overseer.core.Overseer;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkConstants;
import org.slf4j.Logger;

/**
 * Forge 1.20.1 entry point.
 *
 * <p>Overseer is server-side only: clients without the mod can join. The optional
 * client part (icon exporter, M2) activates only when installed on a client.
 */
@Mod(Overseer.MOD_ID)
public final class OverseerForge {
    private static final Logger LOGGER = LogUtils.getLogger();

    public OverseerForge(FMLJavaModLoadingContext context) {
        // Tell Forge that the server-side presence of this mod must not be required on clients.
        context.registerExtensionPoint(IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(() -> NetworkConstants.IGNORESERVERONLY, (remote, isServer) -> true));
        LOGGER.info("{} loaded", Overseer.NAME);
    }
}
