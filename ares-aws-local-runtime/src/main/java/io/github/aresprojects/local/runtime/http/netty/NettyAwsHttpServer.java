package io.github.aresprojects.local.runtime.http.netty;

import io.github.aresprojects.local.runtime.LocalAwsServerConfig;
import io.github.aresprojects.local.runtime.http.AwsHttpResponse;
import io.github.aresprojects.local.runtime.http.AwsRequestContext;
import io.github.aresprojects.local.runtime.http.AwsRequestHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

public final class NettyAwsHttpServer implements AutoCloseable {
    private static final String HEALTH_PATH = "/_ares/health";
    private static final AwsHttpResponse HEALTH_RESPONSE = AwsHttpResponse.json(200, "{\"status\":\"ok\"}");
    private static final AwsHttpResponse INTERNAL_ERROR_RESPONSE =
            AwsHttpResponse.json(500, "{\"error\":\"request handler failed or returned no response\"}");
    private static final AwsHttpResponse BAD_REQUEST_RESPONSE =
            AwsHttpResponse.json(400, "{\"error\":\"malformed or unsupported HTTP request\"}");
    private static final AwsHttpResponse REQUEST_TOO_LARGE_RESPONSE =
            AwsHttpResponse.json(413, "{\"error\":\"request body exceeds maxRequestBytes\"}");

    private final LocalAwsServerConfig config;
    private final AwsRequestHandler handler;
    private final Clock clock;
    private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    private final EventLoopGroup workerGroup = new NioEventLoopGroup();
    private Channel serverChannel;

    public NettyAwsHttpServer(LocalAwsServerConfig config, AwsRequestHandler handler) {
        this(config, handler, Clock.systemUTC());
    }

    NettyAwsHttpServer(LocalAwsServerConfig config, AwsRequestHandler handler, Clock clock) {
        this.config = config;
        this.handler = handler;
        this.clock = clock;
    }

    public InetSocketAddress start() {
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.AUTO_READ, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channel.pipeline()
                                .addLast(new HttpServerCodec())
                                .addLast(new io.netty.handler.codec.http.HttpObjectAggregator(config.maxRequestBytes()))
                                .addLast(new RequestHandler());
                    }
                });
        try {
            serverChannel = bootstrap.bind(config.host(), config.port()).sync().channel();
            return (InetSocketAddress) serverChannel.localAddress();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            close();
            throw new IllegalStateException("Interrupted while starting the HTTP server", exception);
        } catch (RuntimeException exception) {
            close();
            throw exception;
        }
    }

    @Override
    public void close() {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
            serverChannel = null;
        }
        bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
        workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
    }

    private final class RequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) {
            boolean keepAlive = HttpUtil.isKeepAlive(request);
            if (!request.protocolVersion().equals(HttpVersion.HTTP_1_1)) {
                writeResponse(context, BAD_REQUEST_RESPONSE, false);
                return;
            }
            if (request.method().equals(HttpMethod.GET) && request.uri().equals(HEALTH_PATH)) {
                writeResponse(context, HEALTH_RESPONSE, keepAlive);
                return;
            }

            AwsRequestContext requestContext = toRequestContext(context, request);
            CompletionStage<AwsHttpResponse> responseStage;
            try {
                responseStage = handler.handle(requestContext);
            } catch (RuntimeException exception) {
                writeResponse(context, INTERNAL_ERROR_RESPONSE, keepAlive);
                return;
            }
            if (responseStage == null) {
                writeResponse(context, INTERNAL_ERROR_RESPONSE, keepAlive);
                return;
            }
            responseStage.whenComplete((response, failure) -> {
                AwsHttpResponse resolved = failure == null && response != null ? response : INTERNAL_ERROR_RESPONSE;
                writeResponse(context, resolved, keepAlive);
            });
        }

        private AwsRequestContext toRequestContext(ChannelHandlerContext context, FullHttpRequest request) {
            Map<String, List<String>> headers = new LinkedHashMap<>();
            request.headers()
                    .forEach(entry -> headers.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>())
                            .add(entry.getValue()));
            byte[] body = ByteBufUtil.getBytes(request.content());
            return new AwsRequestContext(
                    UUID.randomUUID().toString(),
                    Instant.now(clock),
                    request.method().name(),
                    request.protocolVersion().text(),
                    request.uri(),
                    headers,
                    body,
                    (InetSocketAddress) context.channel().remoteAddress(),
                    (InetSocketAddress) context.channel().localAddress());
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            AwsHttpResponse response =
                    cause instanceof TooLongFrameException ? REQUEST_TOO_LARGE_RESPONSE : BAD_REQUEST_RESPONSE;
            writeResponse(context, response, false);
        }
    }

    private void writeResponse(ChannelHandlerContext context, AwsHttpResponse response, boolean keepAlive) {
        if (!context.channel().isActive()) {
            return;
        }
        DefaultFullHttpResponse nettyResponse = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(response.statusCode()),
                Unpooled.wrappedBuffer(response.body()));
        response.headers()
                .forEach((name, values) ->
                        values.forEach(value -> nettyResponse.headers().add(name, value)));
        nettyResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.body().length);
        if (!keepAlive) {
            nettyResponse.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            context.writeAndFlush(nettyResponse).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
        } else {
            context.writeAndFlush(nettyResponse);
        }
    }
}
