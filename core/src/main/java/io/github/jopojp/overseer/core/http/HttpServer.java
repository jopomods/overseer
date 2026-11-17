package io.github.jopojp.overseer.core.http;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Overseer's web server on Netty (ADR-0003).
 *
 * <p>Netty threads only parse and write; handlers run on a small worker pool so database
 * reads never block the event loop. API routes come from the {@link Router}; everything
 * else is served from the embedded UI bundle.
 */
public final class HttpServer implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(HttpServer.class);

    private final HttpConfig config;
    private final Router router;
    private final StaticFiles staticFiles;
    private final SseHub sse;
    private EventLoopGroup boss;
    private EventLoopGroup workers;
    private ExecutorService handlers;
    private Channel serverChannel;

    public HttpServer(HttpConfig config, Router router, StaticFiles staticFiles, SseHub sse) {
        this.config = config;
        this.router = router;
        this.staticFiles = staticFiles;
        this.sse = sse;
    }

    public synchronized void start() throws InterruptedException {
        if (serverChannel != null) throw new IllegalStateException("already started");
        boss = new NioEventLoopGroup(1, threads("overseer-http-accept"));
        workers = new NioEventLoopGroup(2, threads("overseer-http-io"));
        handlers = Executors.newFixedThreadPool(4, threads("overseer-http-worker"));
        var bootstrap = new ServerBootstrap()
                .group(boss, workers)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new HttpServerCodec(4096, 16 * 1024, 16 * 1024))
                                .addLast(new HttpObjectAggregator(config.maxRequestBytes()))
                                .addLast(new RequestHandler());
                    }
                });
        serverChannel = bootstrap.bind(config.bindAddress(), config.port()).sync().channel();
        LOG.info("Overseer dashboard listening on http://{}:{}", config.bindAddress(), port());
    }

    /** The bound port (useful when configured with port 0). */
    public int port() {
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    @Override
    public synchronized void close() {
        if (serverChannel == null) return;
        serverChannel.close().syncUninterruptibly();
        handlers.shutdownNow();
        boss.shutdownGracefully(0, 2, TimeUnit.SECONDS);
        workers.shutdownGracefully(0, 2, TimeUnit.SECONDS);
        serverChannel = null;
    }

    private final class RequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest raw) {
            boolean keepAlive = HttpUtil.isKeepAlive(raw);
            if (!raw.decoderResult().isSuccess()) {
                write(ctx, Response.error(HttpError.badRequest("Malformed request.")), false);
                return;
            }
            Request request = toRequest(ctx, raw);
            try {
                handlers.execute(() -> {
                    Response response = dispatch(request);
                    if (response.isStream()) {
                        startStream(ctx, response);
                    } else {
                        write(ctx, response, keepAlive);
                    }
                });
            } catch (RejectedExecutionException e) {
                write(ctx, Response.error(new HttpError(503, "unavailable", "Server is shutting down.")), false);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            LOG.debug("Overseer HTTP connection error", cause);
            ctx.close();
        }
    }

    Response dispatch(Request request) {
        try {
            if (request.path().startsWith("/api/")) {
                if (OriginCheck.needsCheck(request.method(), request.bearerToken().isPresent())) {
                    String host = request.header("x-overseer-effective-host").orElse(null);
                    if (!OriginCheck.sameOrigin(request.header("origin").orElse(null), host, request.secure())) {
                        throw HttpError.forbidden("Cross-origin request rejected.");
                    }
                }
                Optional<Router.Match> match = router.match(request);
                if (match.isEmpty()) throw HttpError.notFound();
                return withApiHeaders(match.get().handler().handle(match.get().request()));
            }
            if (!request.method().equals("GET") && !request.method().equals("HEAD")) {
                throw new HttpError(405, "method_not_allowed", "Method not allowed.");
            }
            return staticFiles.serve(request.path()).orElseGet(() -> Response.error(HttpError.notFound()));
        } catch (HttpError e) {
            return withApiHeaders(Response.error(e));
        } catch (Exception e) {
            LOG.error("Overseer HTTP handler failed for {} {}", request.method(), request.path(), e);
            return withApiHeaders(Response.error(new HttpError(500, "internal", "Internal server error.")));
        }
    }

    private static Response withApiHeaders(Response response) {
        response.headers().putIfAbsent("Cache-Control", "no-store");
        return response;
    }

    private Request toRequest(ChannelHandlerContext ctx, FullHttpRequest raw) {
        var decoder = new QueryStringDecoder(raw.uri());
        Map<String, String> headers = new HashMap<>();
        raw.headers().forEach(h -> headers.putIfAbsent(h.getKey().toLowerCase(Locale.ROOT), h.getValue()));

        String peer = ((InetSocketAddress) ctx.channel().remoteAddress()).getAddress().getHostAddress();
        String client = ClientAddress.resolve(peer, headers.get("x-forwarded-for"), config.trustedProxies());
        boolean secure = ClientAddress.isSecure(peer, headers.get("x-forwarded-proto"), config.trustedProxies(),
                config.forceSecureCookies());
        String host = ClientAddress.host(peer, headers.get("host"), headers.get("x-forwarded-host"),
                config.trustedProxies());
        // Internal, computed header; any client-supplied value is overwritten.
        if (host != null) headers.put("x-overseer-effective-host", host); else headers.remove("x-overseer-effective-host");

        byte[] body = new byte[raw.content().readableBytes()];
        raw.content().getBytes(raw.content().readerIndex(), body);
        return new Request(raw.method().name().toUpperCase(Locale.ROOT), decoder.path(), decoder.parameters(),
                Map.copyOf(headers), Cookies.parse(headers.get("cookie")), body, client, secure, Map.of());
    }

    private static void write(ChannelHandlerContext ctx, Response response, boolean keepAlive) {
        FullHttpResponse out = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(response.status()), Unpooled.wrappedBuffer(response.body()));
        applyHeaders(out, response);
        HttpUtil.setContentLength(out, response.body().length);
        if (keepAlive) {
            out.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            ctx.writeAndFlush(out);
        } else {
            out.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            ctx.writeAndFlush(out).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private void startStream(ChannelHandlerContext ctx, Response response) {
        HttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        applyHeaders(head, response);
        head.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store");
        head.headers().set("X-Accel-Buffering", "no"); // nginx: do not buffer the stream
        HttpUtil.setTransferEncodingChunked(head, true);
        ctx.writeAndFlush(head);
        sse.register(ctx.channel(), response.streamPrincipal());
    }

    private static void applyHeaders(HttpResponse out, Response response) {
        response.headers().forEach((name, value) -> out.headers().set(name, value));
        for (String cookie : response.cookies()) out.headers().add(HttpHeaderNames.SET_COOKIE, cookie);
        out.headers().set("X-Content-Type-Options", "nosniff");
        out.headers().set("Referrer-Policy", "no-referrer");
        out.headers().set("X-Frame-Options", "DENY");
    }

    private static ThreadFactory threads(String name) {
        var counter = new AtomicInteger();
        return r -> {
            var thread = new Thread(r, name + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
