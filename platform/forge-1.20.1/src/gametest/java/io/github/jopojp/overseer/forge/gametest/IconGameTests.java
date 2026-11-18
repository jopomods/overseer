package io.github.jopojp.overseer.forge.gametest;

import io.github.jopojp.overseer.core.Overseer;
import io.github.jopojp.overseer.core.storage.IconStore;
import io.github.jopojp.overseer.forge.icons.IconChannel;
import io.github.jopojp.overseer.forge.icons.JarTextures;
import io.netty.buffer.Unpooled;
import java.util.Arrays;
import java.util.LinkedHashMap;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Icon fallback from mod jars and the icon packet format. */
@GameTestHolder(Overseer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class IconGameTests {
    private IconGameTests() {}

    @GameTest(template = "empty")
    public static void jarTexturesFindPlainItemTextures(GameTestHelper helper) {
        helper.assertTrue(JarTextures.find("item:minecraft:../../secret") == null, "no path traversal");
        helper.assertTrue(JarTextures.find("fluid:minecraft:water") == null, "fluids have no jar fallback");
        helper.assertTrue(JarTextures.find("item:nosuchmod:thing") == null, "unknown mods have no textures");
        if (ModList.get().isLoaded("ae2")) {
            byte[] png = JarTextures.find("item:ae2:certus_quartz_crystal");
            helper.assertTrue(IconStore.isPng(png), "AE2 crystal texture from the jar");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void namesLoseFormattingCodes(GameTestHelper helper) {
        String plain = io.github.jopojp.overseer.forge.integration.ae2.Ae2Names.plain("§5EV §fMachine Hull");
        helper.assertTrue("EV Machine Hull".equals(plain), "plain: " + plain);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void iconBatchesFitOnePacketAndRoundTrip(GameTestHelper helper) {
        var icons = new LinkedHashMap<String, byte[]>();
        byte[] png = Arrays.copyOf(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'}, 1_500);
        for (int i = 0; i < 100; i++) icons.put("item:example:thing_" + i, png);
        var batches = IconChannel.batches(icons);
        helper.assertTrue(batches.size() > 1, "100 icons of 1.5 kB need several packets");
        int count = 0;
        for (var batch : batches) {
            var buf = new FriendlyByteBuf(Unpooled.buffer());
            IconChannel.IconBatch.encode(batch, buf);
            helper.assertTrue(buf.readableBytes() < 32_767, "fits a serverbound payload: " + buf.readableBytes());
            var decoded = IconChannel.IconBatch.decode(buf);
            helper.assertTrue(decoded.icons().keySet().equals(batch.icons().keySet()), "keys survive");
            count += decoded.icons().size();
        }
        helper.assertTrue(count == 100, "all icons sent: " + count);
        helper.succeed();
    }
}
