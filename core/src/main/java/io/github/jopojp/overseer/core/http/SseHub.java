package io.github.jopojp.overseer.core.http;

import io.github.jopojp.overseer.core.access.AccessService;
import io.github.jopojp.overseer.core.api.Dtos;
import io.github.jopojp.overseer.core.auth.AuthService;
import io.github.jopojp.overseer.core.auth.Principal;
import io.github.jopojp.overseer.core.pipeline.LatestState;
import io.github.jopojp.overseer.core.provider.ProviderSnapshot;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.DefaultHttpContent;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pushes live snapshots to Server-Sent Events clients (ADR-0004, ADR-0008).
 *
 * <p>Snapshots arrive from the server thread via {@link #publish}, which only enqueues.
 * Fan-out runs on the hub's own thread: every event is filtered for each client's
 * principal, and principals are re-validated at least every {@value #REVALIDATE_MILLIS} ms,
 * so a revoked session or a demoted admin stops receiving data promptly.
 */
public final class SseHub implements AutoCloseable {
    static final long KEEPALIVE_SECONDS = 15;
    static final long REVALIDATE_MILLIS = 5_000;

    private static final Logger LOG = LoggerFactory.getLogger(SseHub.class);

    private static final class Client {
        final Channel channel;
        volatile Principal principal;
        volatile long validatedAtMillis;

        Client(Channel channel, Principal principal) {
            this.channel = channel;
            this.principal = principal;
            this.validatedAtMillis = System.currentTimeMillis();
        }
    }

    private final AccessService access;
    private final AuthService auth;
    private final LatestState latest;
    private final java.util.function.Consumer<ProviderSnapshot> listener = this::publish;
    private final ConcurrentHashMap<Channel, Client> clients = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "overseer-sse");
        t.setDaemon(true);
        return t;
    });

    public SseHub(AccessService access, AuthService auth, LatestState latest) {
        this.access = access;
        this.auth = auth;
        this.latest = latest;
        latest.addListener(listener);
        executor.scheduleAtFixedRate(this::keepAlive, KEEPALIVE_SECONDS, KEEPALIVE_SECONDS, TimeUnit.SECONDS);
    }

    /** Registers an open event stream and sends the current state. */
    void register(Channel channel, Principal principal) {
        var client = new Client(channel, principal);
        clients.put(channel, client);
        channel.closeFuture().addListener(f -> clients.remove(channel));
        executor.execute(() -> latest.all().values().forEach(s -> send(client, s)));
    }

    /** Called on the server thread for every new snapshot; must stay cheap. */
    public void publish(ProviderSnapshot snapshot) {
        if (clients.isEmpty()) return;
        executor.execute(() -> {
            for (Client client : clients.values()) send(client, snapshot);
        });
    }

    public int clientCount() {
        return clients.size();
    }

    private void send(Client client, ProviderSnapshot snapshot) {
        if (!client.channel.isActive()) return;
        Optional<Principal> principal = currentPrincipal(client);
        if (principal.isEmpty()) {
            client.channel.close();
            return;
        }
        var filtered = access.filter(principal.get(), snapshot);
        Principal viewer = principal.get();
        String json = Json.GSON.toJson(Dtos.snapshot(filtered, ref -> access.canEdit(viewer, ref)));
        write(client, "event: snapshot\ndata: " + json + "\n\n");
    }

    private Optional<Principal> currentPrincipal(Client client) {
        long now = System.currentTimeMillis();
        if (now - client.validatedAtMillis < REVALIDATE_MILLIS) return Optional.of(client.principal);
        try {
            Optional<Principal> revalidated = auth.revalidate(client.principal);
            revalidated.ifPresent(p -> client.principal = p);
            client.validatedAtMillis = now;
            return revalidated;
        } catch (Exception e) {
            LOG.warn("Overseer could not revalidate an SSE client; closing it", e);
            return Optional.empty();
        }
    }

    private void keepAlive() {
        for (Client client : clients.values()) {
            if (currentPrincipal(client).isEmpty()) {
                client.channel.close();
            } else {
                write(client, ": keepalive\n\n");
            }
        }
    }

    private static void write(Client client, String text) {
        client.channel.writeAndFlush(new DefaultHttpContent(
                Unpooled.copiedBuffer(text, StandardCharsets.UTF_8)));
    }

    @Override
    public void close() {
        latest.removeListener(listener);
        executor.shutdownNow();
        clients.keySet().forEach(Channel::close);
        clients.clear();
    }
}
