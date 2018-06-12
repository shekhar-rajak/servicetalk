/*
 * Copyright © 2018 Apple Inc. and the ServiceTalk project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.servicetalk.tcp.netty.internal;

import io.servicetalk.buffer.netty.BufferUtil;
import io.servicetalk.concurrent.api.Executor;
import io.servicetalk.concurrent.api.Single;
import io.servicetalk.transport.api.ContextFilter;
import io.servicetalk.transport.api.ServerContext;
import io.servicetalk.transport.netty.internal.BuilderUtils;
import io.servicetalk.transport.netty.internal.ChannelInitializer;
import io.servicetalk.transport.netty.internal.CloseHandler;
import io.servicetalk.transport.netty.internal.EventLoopAwareNettyIoExecutor;
import io.servicetalk.transport.netty.internal.NettyIoExecutor;
import io.servicetalk.transport.netty.internal.NettyServerContext;
import io.servicetalk.transport.netty.internal.RefCountedTrapper;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;
import java.util.Map;
import javax.annotation.Nullable;

import static io.netty.channel.ChannelOption.ALLOW_HALF_CLOSURE;
import static io.netty.channel.ChannelOption.AUTO_CLOSE;
import static io.servicetalk.concurrent.api.Executors.newCachedThreadExecutor;
import static io.servicetalk.transport.netty.internal.BuilderUtils.toNettyAddress;
import static io.servicetalk.transport.netty.internal.NettyConnectionContext.newContext;
import static java.util.Objects.requireNonNull;

/**
 * Utility class to start a TCP based server.
 */
public final class TcpServerInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(TcpServerInitializer.class);

    private final EventLoopAwareNettyIoExecutor nettyIoExecutor;
    private final ReadOnlyTcpServerConfig config;

    /**
     * New instance.
     * @param config to use for initialization.
     */
    public TcpServerInitializer(ReadOnlyTcpServerConfig config) {
        this.config = config;
        NettyIoExecutor ioExecutor = config.getIoExecutor();
        if (!(ioExecutor instanceof EventLoopAwareNettyIoExecutor)) {
            throw new IllegalArgumentException("Incompatible NettyIoExecutor: " + ioExecutor +
                    ". Not aware of netty eventloops.");
        }
        nettyIoExecutor = ((EventLoopAwareNettyIoExecutor) ioExecutor);
    }

    /**
     * Starts a server listening on the {@code listenAddress}.
     *
     * @param listenAddress for the server.
     * @return Single which completes when the server is started.
     */
    public Single<ServerContext> startWithDefaults(SocketAddress listenAddress) {
        return startWithDefaults(listenAddress, ContextFilter.ACCEPT_ALL);
    }

    /**
     * Starts a server listening on the {@code listenAddress}.
     *
     * @param listenAddress for the server.
     * @param contextFilter to use for filtering accepted connections.
     * @return Single which completes when the server is started.
     */
    public Single<ServerContext> startWithDefaults(SocketAddress listenAddress, ContextFilter contextFilter) {
        return start(listenAddress, contextFilter, new TcpServerChannelInitializer(config));
    }

    /**
     * Starts a server using the passed {@code channelInitializer} on the {@code listenAddress}.
     *
     * @param listenAddress for the server.
     * @param contextFilter to use for filtering accepted connections.
     * @param channelInitializer to use for initializing all accepted connections.
     * @return {@link Single} which completes when the server is started.
     */
    public Single<ServerContext> start(SocketAddress listenAddress, ContextFilter contextFilter,
                                       ChannelInitializer channelInitializer) {
        return start(listenAddress, contextFilter, channelInitializer, newCachedThreadExecutor());
    }

    /**
     * Starts a server using the passed {@code channelInitializer} on the {@code listenAddress}.
     *
     * @param listenAddress for the server.
     * @param contextFilter to use for filtering accepted connections.
     * @param executor The {@link Executor} for invoking {@code contextFilter} and {@code channelInitializer}.
     * @param channelInitializer to use for initializing all accepted connections.
     * @return {@link Single} which completes when the server is started.
     */
    public Single<ServerContext> start(SocketAddress listenAddress, ContextFilter contextFilter,
                                       ChannelInitializer channelInitializer, Executor executor) {
        return start(listenAddress, contextFilter, channelInitializer, executor, true, false);
    }

    /**
     * Starts a server using the passed {@code channelInitializer} on the {@code listenAddress}.
     *
     * @param listenAddress for the server.
     * @param contextFilter to use for filtering accepted connections.
     * @param executor The {@link Executor} for invoking {@code contextFilter} and {@code channelInitializer}.
     * @param channelInitializer to use for initializing all accepted connections.
     * @param checkForRefCountedTrapper Whether to log a warning if a {@link RefCountedTrapper} is not found in the
     * pipeline.
     * @param enableHalfClosure whether half-closure should be enabled and a handler will be installed to manage closure
     * @return {@link Single} which completes when the server is started.
     * @see CloseHandler to manage half-closing connections from the protocol
     */
    public Single<ServerContext> start(SocketAddress listenAddress, ContextFilter contextFilter,
                                       ChannelInitializer channelInitializer, Executor executor,
                                       boolean checkForRefCountedTrapper, boolean enableHalfClosure) {
        requireNonNull(channelInitializer);
        requireNonNull(contextFilter);
        listenAddress = toNettyAddress(requireNonNull(listenAddress));
        ServerBootstrap bs = new ServerBootstrap();
        // The ConnectionContext should be given an IoExecutor which correlates to the specific thread used for IO,
        // so we select it here up front.
        EventLoopAwareNettyIoExecutor threadIoExecutor = nettyIoExecutor.next();
        // next() of an EventLoop will just return itself, which is expected because we did the selection above.
        EventLoop eventLoop = threadIoExecutor.getEventLoopGroup().next();
        configure(bs, eventLoop, listenAddress.getClass(), enableHalfClosure);
        //TODO: AdvancedChannelGroup is missing from ST 1.x.
        bs.childHandler(new io.netty.channel.ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel channel) {
                executor.execute(() -> {
                    try {
                        newContext(channel, threadIoExecutor, executor, config.getAllocator(),
                                new ContextFilterChannelInitializer(contextFilter, channelInitializer),
                                checkForRefCountedTrapper);
                    } catch (Throwable t) {
                        LOGGER.warn("Failed to initialize a channel {}. Closing" + channel, t);
                        channel.close();
                    }
                });
            }
        });
        ChannelFuture future = bs.bind(listenAddress);
        return new Single<ServerContext>() {
            @Override
            protected void handleSubscribe(Subscriber<? super ServerContext> subscriber) {
                subscriber.onSubscribe(() -> future.cancel(true));
                ChannelFutureListener channelFutureListener = f -> {
                    if (f.isSuccess()) {
                        subscriber.onSuccess(NettyServerContext.wrap(f.channel(), contextFilter.closeAsync()));
                    } else if (f.cause() != null) {
                        subscriber.onError(f.cause());
                    } else {
                        // Bind cancelled, so close the channel.
                        f.channel().close();
                    }
                };
                future.addListener(channelFutureListener);
            }
        };
    }

    @SuppressWarnings("deprecation")
    private void configure(ServerBootstrap bs, @Nullable EventLoopGroup eventLoopGroup,
                           Class<? extends SocketAddress> bindAddressClass, final boolean enableHalfClosure) {
        if (eventLoopGroup == null) {
            throw new IllegalStateException("IoExecutor must be specified before building");
        }
        bs.group(eventLoopGroup);
        bs.channel(BuilderUtils.serverChannel(eventLoopGroup, bindAddressClass));

        for (@SuppressWarnings("rawtypes") Map.Entry<ChannelOption, Object> opt : config.getOptions().entrySet()) {
            @SuppressWarnings("unchecked")
            ChannelOption<Object> option = opt.getKey();
            bs.childOption(option, opt.getValue());
        }

        // we disable auto read so we can handle stuff in the ConnectionFilter before we accept any content.
        bs.childOption(ChannelOption.AUTO_READ, config.isAutoRead());
        if (!config.isAutoRead()) {
            bs.childOption(ChannelOption.MAX_MESSAGES_PER_READ, 1);
        }

        if (enableHalfClosure) {
            bs.childOption(ALLOW_HALF_CLOSURE, true);
            bs.childOption(AUTO_CLOSE, false);
        }

        bs.option(ChannelOption.SO_BACKLOG, config.getBacklog());

        // Set the correct ByteBufAllocator based on our BufferAllocator to minimize memory copies.
        bs.option(ChannelOption.ALLOCATOR, BufferUtil.getByteBufAllocator(config.getAllocator()));
        bs.childOption(ChannelOption.ALLOCATOR, BufferUtil.getByteBufAllocator(config.getAllocator()));
    }
}
