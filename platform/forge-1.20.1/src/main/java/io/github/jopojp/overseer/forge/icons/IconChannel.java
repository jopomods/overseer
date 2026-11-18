package io.github.jopojp.overseer.forge.icons;

import io.github.jopojp.overseer.core.Overseer;
import io.github.jopojp.overseer.core.storage.IconStore;
import io.github.jopojp.overseer.forge.ServerLifecycle;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Network channel for icons rendered by a client with Overseer installed ({@code /ovicons export}).
 * Optional on both sides: clients and servers without it still connect. Only operators may send
 * icons; the server stores them off the server thread.
 */
public final class IconChannel {
    private static final Logger LOG = LoggerFactory.getLogger(IconChannel.class);
    private static final String VERSION = "1";
    /** Serverbound custom payloads are limited to 32 KiB; batches stay below. */
    public static final int MAX_BATCH_BYTES = 30_000;
    public static final int MAX_BATCH_ICONS = 256;
    /** Per player and server run: more than any modpack has items and fluids. */
    private static final int MAX_ICONS_PER_PLAYER = 200_000;

    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(ResourceLocation.tryBuild(Overseer.MOD_ID, "icons"))
            .networkProtocolVersion(() -> VERSION)
            .clientAcceptedVersions(v -> true)
            .serverAcceptedVersions(v -> true)
            .simpleChannel();

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "overseer-icons");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<UUID, Integer> RECEIVED = new ConcurrentHashMap<>();

    private IconChannel() {}

    /** A batch of icons: stack key to PNG. */
    public record IconBatch(Map<String, byte[]> icons) {
        public static void encode(IconBatch batch, FriendlyByteBuf buf) {
            buf.writeVarInt(batch.icons().size());
            batch.icons().forEach((key, png) -> {
                buf.writeUtf(key, 512);
                buf.writeByteArray(png);
            });
        }

        public static IconBatch decode(FriendlyByteBuf buf) {
            int count = buf.readVarInt();
            if (count < 0 || count > MAX_BATCH_ICONS) throw new IllegalArgumentException("too many icons: " + count);
            var icons = new LinkedHashMap<String, byte[]>();
            for (int i = 0; i < count; i++) icons.put(buf.readUtf(512), buf.readByteArray(IconStore.MAX_PNG_BYTES));
            return new IconBatch(icons);
        }
    }

    public static void register() {
        CHANNEL.messageBuilder(IconBatch.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder(IconBatch::encode)
                .decoder(IconBatch::decode)
                .consumerMainThread(IconChannel::receive)
                .add();
    }

    private static void receive(IconBatch batch, Supplier<NetworkEvent.Context> context) {
        context.get().setPacketHandled(true);
        ServerPlayer player = context.get().getSender();
        if (player == null || !player.hasPermissions(2)) return;
        int total = RECEIVED.merge(player.getUUID(), batch.icons().size(), Integer::sum);
        if (total > MAX_ICONS_PER_PLAYER) return;
        var runtime = ServerLifecycle.runtime().orElse(null);
        if (runtime == null) return;
        UUID by = player.getUUID();
        // No database work on the server thread (ADR-0004).
        WRITER.execute(() -> {
            try {
                runtime.icons().putAll(batch.icons(), by, Instant.now());
            } catch (Exception e) {
                LOG.warn("Overseer could not store {} icons", batch.icons().size(), e);
            }
        });
    }

    /** Splits icons into batches that fit one packet. */
    public static List<IconBatch> batches(Map<String, byte[]> icons) {
        var result = new ArrayList<IconBatch>();
        var current = new LinkedHashMap<String, byte[]>();
        int bytes = 0;
        for (var e : icons.entrySet()) {
            int size = e.getKey().length() * 3 + e.getValue().length + 10;
            if (!current.isEmpty() && (bytes + size > MAX_BATCH_BYTES || current.size() >= MAX_BATCH_ICONS)) {
                result.add(new IconBatch(current));
                current = new LinkedHashMap<>();
                bytes = 0;
            }
            current.put(e.getKey(), e.getValue());
            bytes += size;
        }
        if (!current.isEmpty()) result.add(new IconBatch(current));
        return result;
    }
}
