package io.github.jopojp.overseer.forge.client;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import io.github.jopojp.overseer.core.storage.IconStore;
import io.github.jopojp.overseer.forge.icons.IconChannel;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client only: {@code /ovicons export} renders the icon of every item and fluid into a small
 * off-screen target and sends the PNGs to the server over {@link IconChannel} (operators only).
 * Spread over ticks so the game keeps running. Only loaded on clients that have Overseer.
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT)
public final class IconExporter {
    private static final Logger LOG = LoggerFactory.getLogger(IconExporter.class);
    /** Pixels per icon: items are 16 px, rendered at 2x so the dashboard stays sharp. */
    private static final int SIZE = 32;
    private static final int RENDERS_PER_TICK = 48;
    private static final int BATCHES_PER_TICK = 4;

    private static Deque<Object> todo;
    private static final Map<String, byte[]> rendered = new LinkedHashMap<>();
    private static Deque<IconChannel.IconBatch> outgoing;
    private static int total;
    private static int sent;
    private static TextureTarget target;

    private IconExporter() {}

    @SubscribeEvent
    public static void commands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ovicons")
                .then(Commands.literal("export").executes(ctx -> start())));
    }

    private static int start() {
        var mc = Minecraft.getInstance();
        if (todo != null || outgoing != null) return message("An export is already running.", ChatFormatting.YELLOW);
        var connection = mc.getConnection();
        if (connection == null || !IconChannel.CHANNEL.isRemotePresent(connection.getConnection())) {
            return message("This server has no Overseer, or an older one without icon support.", ChatFormatting.RED);
        }
        if (mc.player == null || !mc.player.hasPermissions(2)) {
            return message("Only operators can export icons.", ChatFormatting.RED);
        }
        todo = new ArrayDeque<>();
        for (Item item : ForgeRegistries.ITEMS) if (item != Items.AIR) todo.add(item);
        for (Fluid fluid : ForgeRegistries.FLUIDS) if (fluid.isSource(fluid.defaultFluidState())) todo.add(fluid);
        total = todo.size();
        rendered.clear();
        message("Rendering " + total + " icons...", ChatFormatting.GRAY);
        return 1;
    }

    @SubscribeEvent
    public static void tick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        try {
            if (todo != null) renderSome();
            else if (outgoing != null) sendSome();
        } catch (RuntimeException e) {
            LOG.error("Overseer icon export failed", e);
            message("Icon export failed: " + e.getMessage(), ChatFormatting.RED);
            todo = null;
            outgoing = null;
        }
    }

    private static void renderSome() {
        var mc = Minecraft.getInstance();
        for (int i = 0; i < RENDERS_PER_TICK && !todo.isEmpty(); i++) {
            Object next = todo.poll();
            try {
                if (next instanceof Item item) {
                    byte[] png = render(g -> g.renderItem(new ItemStack(item), 0, 0));
                    if (png != null) rendered.put("item:" + ForgeRegistries.ITEMS.getKey(item), png);
                } else if (next instanceof Fluid fluid) {
                    byte[] png = render(g -> fluid(g, fluid));
                    if (png != null) rendered.put("fluid:" + ForgeRegistries.FLUIDS.getKey(fluid), png);
                }
            } catch (RuntimeException e) {
                LOG.debug("Overseer could not render {}", next, e); // one broken model must not stop the export
            }
        }
        mc.getMainRenderTarget().bindWrite(true);
        if (todo.isEmpty()) {
            todo = null;
            outgoing = new ArrayDeque<>(IconChannel.batches(rendered));
            sent = 0;
            message("Rendered " + rendered.size() + " of " + total + " icons, sending...", ChatFormatting.GRAY);
        }
    }

    private static void sendSome() {
        for (int i = 0; i < BATCHES_PER_TICK && !outgoing.isEmpty(); i++) {
            var batch = outgoing.poll();
            IconChannel.CHANNEL.sendToServer(batch);
            sent += batch.icons().size();
        }
        if (outgoing.isEmpty()) {
            outgoing = null;
            message("Sent " + sent + " icons to Overseer. The dashboard shows them after a reload.", ChatFormatting.GREEN);
            rendered.clear();
        }
    }

    private interface Draw {
        void draw(GuiGraphics graphics);
    }

    /** Renders one 16x16 GUI drawing into a transparent SIZE x SIZE PNG; null when empty. */
    private static byte[] render(Draw draw) {
        var mc = Minecraft.getInstance();
        if (target == null) target = new TextureTarget(SIZE, SIZE, true, Minecraft.ON_OSX);
        target.setClearColor(0, 0, 0, 0);
        target.clear(Minecraft.ON_OSX);
        target.bindWrite(true);

        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, 16, 16, 0, 1000, 21000), VertexSorting.ORTHOGRAPHIC_Z);
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        modelView.setIdentity();
        modelView.translate(0, 0, -11000);
        RenderSystem.applyModelViewMatrix();
        try {
            var graphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            draw.draw(graphics);
            graphics.flush();
        } finally {
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
        }

        try (var image = new NativeImage(SIZE, SIZE, false)) {
            RenderSystem.bindTexture(target.getColorTextureId());
            image.downloadTexture(0, false);
            image.flipY();
            boolean empty = true;
            for (int y = 0; y < SIZE && empty; y++) {
                for (int x = 0; x < SIZE && empty; x++) {
                    if ((image.getPixelRGBA(x, y) >>> 24) != 0) empty = false;
                }
            }
            if (empty) return null;
            byte[] png = image.asByteArray();
            return IconStore.isPng(png) ? png : null;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** A fluid's still texture, tinted like in tanks. */
    private static void fluid(GuiGraphics graphics, Fluid fluid) {
        var mc = Minecraft.getInstance();
        var ext = IClientFluidTypeExtensions.of(fluid);
        var still = ext.getStillTexture();
        if (still == null) return;
        var sprite = mc.getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(still);
        int tint = ext.getTintColor();
        float a = ((tint >>> 24) & 0xff) / 255f;
        graphics.blit(0, 0, 0, 16, 16, sprite, ((tint >> 16) & 0xff) / 255f, ((tint >> 8) & 0xff) / 255f,
                (tint & 0xff) / 255f, a == 0 ? 1 : a);
    }

    private static int message(String text, ChatFormatting color) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(Component.literal("[Overseer] " + text).withStyle(color), false);
        return 1;
    }
}
