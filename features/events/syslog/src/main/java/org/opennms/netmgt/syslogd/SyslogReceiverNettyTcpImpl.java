/*
 * Licensed to The OpenNMS Group, Inc (TOG) under one or more
 * contributor license agreements.  See the LICENSE.md file
 * distributed with this work for additional information
 * regarding copyright ownership.
 *
 * TOG licenses this file to You under the GNU Affero General
 * Public License Version 3 (the "License") or (at your option)
 * any later version.  You may not use this file except in
 * compliance with the License.  You may obtain a copy of the
 * License at:
 *
 *      https://www.gnu.org/licenses/agpl-3.0.txt
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the
 * License.
 */
package org.opennms.netmgt.syslogd;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

import org.opennms.netmgt.config.SyslogdConfig;
import org.opennms.netmgt.config.syslogd.SyslogTcpConfig;
import org.opennms.netmgt.syslogd.api.SyslogConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.GlobalEventExecutor;

/**
 * Accepts syslog messages over TCP and dispatches them to the Sink API.
 *
 * Runs alongside the UDP receiver rather than replacing it, and is only started when a
 * TCP port has been configured. Netty is used directly rather than through Camel
 * because the Camel netty component has no codec for RFC 6587 octet-counted framing,
 * so a custom decoder is needed either way.
 */
public class SyslogReceiverNettyTcpImpl extends SinkDispatchingSyslogReceiver {

    private static final Logger LOG = LoggerFactory.getLogger(SyslogReceiverNettyTcpImpl.class);

    private static final int SHUTDOWN_TIMEOUT_SECONDS = 15;

    private final SyslogdConfig m_config;

    private SyslogTcpConfig m_tcpConfig;

    private final ChannelGroup m_channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

    private EventLoopGroup m_bossGroup;

    private EventLoopGroup m_workerGroup;

    private ChannelFuture m_socketFuture;

    private SslContext m_sslContext;

    public SyslogReceiverNettyTcpImpl(final SyslogdConfig config) {
        super(config);
        m_config = config;
        m_tcpConfig = config.getTcpConfig();
    }

    @Override
    public String getName() {
        return getClass().getSimpleName() + " [" + describeAddress() + "]";
    }

    public boolean isStarted() {
        return m_socketFuture != null && m_socketFuture.channel().isActive();
    }

    @Override
    public void run() {
        if (!m_tcpConfig.isEnabled()) {
            LOG.debug("Syslog TCP ingestion is not configured, nothing to start");
            return;
        }

        // Built before the socket is bound so that a bad certificate path stops the
        // listener rather than leaving it accepting plaintext on a port that operators
        // believe is encrypted.
        if (m_tcpConfig.isTlsEnabled()) {
            try {
                m_sslContext = SyslogTcpSslContextFactory.create(m_tcpConfig);
                LOG.info("TLS enabled for the syslog TCP listener on {}, client authentication is {}",
                        describeAddress(), m_tcpConfig.getTlsClientAuth());
            } catch (Exception e) {
                LOG.error("Not starting the syslog TCP listener on {}: {}", describeAddress(), e.getMessage(), e);
                return;
            }
        }

        // Creates the sink dispatcher, which every accepted connection then feeds.
        super.run();

        m_bossGroup = new NioEventLoopGroup();
        m_workerGroup = new NioEventLoopGroup();

        try {
            m_socketFuture = new ServerBootstrap()
                    .group(m_bossGroup, m_workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(final SocketChannel ch) {
                            if (m_channels.size() >= m_tcpConfig.getMaxConnections()) {
                                // Logged at debug because a client that retries in a loop
                                // would otherwise fill the log faster than it sends syslog.
                                LOG.debug("Refusing syslog TCP connection from {}: already at the {} connection limit",
                                        ch.remoteAddress(), m_tcpConfig.getMaxConnections());
                                ch.close();
                                return;
                            }
                            m_channels.add(ch);
                            initSyslogPipeline(ch);
                        }
                    })
                    .bind(bindAddress())
                    .sync();

            LOG.info("Listening for syslog messages over TCP on {}", describeAddress());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted while binding the syslog TCP listener on {}", describeAddress(), e);
        } catch (Exception e) {
            LOG.error("Failed to bind the syslog TCP listener on {}", describeAddress(), e);
        }
    }

    private void initSyslogPipeline(final SocketChannel ch) {
        final InetSocketAddress source = ch.remoteAddress();

        // First in the pipeline, so everything after it sees decrypted bytes.
        if (m_sslContext != null) {
            ch.pipeline().addLast(m_sslContext.newHandler(ch.alloc()));
        }

        if (m_tcpConfig.getIdleTimeoutSeconds() > 0) {
            ch.pipeline().addLast(new IdleStateHandler(m_tcpConfig.getIdleTimeoutSeconds(), 0, 0));
            ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                @Override
                public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) {
                    if (evt instanceof IdleStateEvent && ((IdleStateEvent) evt).state() == IdleState.READER_IDLE) {
                        LOG.debug("Closing idle syslog TCP connection from {}", source);
                        ctx.close();
                        return;
                    }
                    ctx.fireUserEventTriggered(evt);
                }
            });
        }

        ch.pipeline().addLast(new SyslogTcpFrameDecoder(source, m_tcpConfig.resolveFraming(),
                m_tcpConfig.getMaxMessageSize()));
        ch.pipeline().addLast(new SyslogDispatchHandler());
        ch.pipeline().addLast(new SyslogTcpExceptionHandler(source));
    }

    /**
     * Hands decoded messages to the sink dispatcher, one at a time and in the order they
     * arrived on the connection.
     *
     * Dispatching concurrently would reorder messages that arrived in a single read,
     * and a sender that chose TCP over UDP is entitled to assume its messages keep the
     * order it sent them in: a link-down followed by a link-up must not arrive reversed.
     *
     * Serialising also carries the backpressure. The sink's async policy blocks when its
     * queue is full, and blocking here would block a Netty worker thread and stall every
     * other connection it serves, so reads are switched off while a dispatch is
     * outstanding and TCP flow control pushes back on the sender instead.
     *
     * One instance per channel, and every field is confined to that channel's event loop.
     */
    private class SyslogDispatchHandler extends SimpleChannelInboundHandler<SyslogConnection> {

        private final Queue<SyslogConnection> pending = new ArrayDeque<>();

        private boolean dispatchInFlight;

        @Override
        protected void channelRead0(final ChannelHandlerContext ctx, final SyslogConnection connection) {
            pending.add(connection);
            ctx.channel().config().setAutoRead(false);
            dispatchNext(ctx);
        }

        private void dispatchNext(final ChannelHandlerContext ctx) {
            if (dispatchInFlight) {
                return;
            }

            final SyslogConnection next = pending.poll();
            if (next == null) {
                ctx.channel().config().setAutoRead(true);
                return;
            }

            dispatchInFlight = true;
            m_dispatcher.send(next).whenComplete((result, ex) -> onDispatched(ctx, ex));
        }

        /** Hops back onto the event loop, because the dispatch completes on a sink thread. */
        private void onDispatched(final ChannelHandlerContext ctx, final Throwable ex) {
            if (ctx.executor().isShuttingDown()) {
                return;
            }
            ctx.executor().execute(() -> {
                dispatchInFlight = false;
                if (ex != null) {
                    ctx.fireExceptionCaught(ex);
                    return;
                }
                dispatchNext(ctx);
            });
        }
    }

    @Override
    public void stop() throws InterruptedException {
        LOG.debug("Stopping the syslog TCP listener on {}", describeAddress());

        m_channels.close().awaitUninterruptibly();

        if (m_socketFuture != null) {
            final Channel channel = m_socketFuture.channel();
            channel.close().awaitUninterruptibly();
            if (channel.parent() != null) {
                channel.parent().close().awaitUninterruptibly();
            }
            m_socketFuture = null;
        }

        // Shut the worker group down before the dispatcher so that no in-flight message
        // is handed to a dispatcher that has already been closed.
        shutdownGracefully(m_workerGroup, "worker");
        m_workerGroup = null;
        shutdownGracefully(m_bossGroup, "boss");
        m_bossGroup = null;

        // Cleared so that a reload which switches TLS off does not keep serving TLS from
        // the context the previous run built.
        m_sslContext = null;

        super.stop();
    }

    private void shutdownGracefully(final EventLoopGroup group, final String description) {
        if (group == null) {
            return;
        }
        if (!group.shutdownGracefully(0, SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .awaitUninterruptibly(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            LOG.warn("Syslog TCP {} group did not shut down within {} seconds", description, SHUTDOWN_TIMEOUT_SECONDS);
        }
    }

    @Override
    public void reload() throws IOException {
        m_config.reload();
        m_tcpConfig = m_config.getTcpConfig();
    }

    private InetSocketAddress bindAddress() {
        return m_tcpConfig.getListenAddress() == null
                ? new InetSocketAddress(m_tcpConfig.getPort())
                : new InetSocketAddress(m_tcpConfig.getListenAddress(), m_tcpConfig.getPort());
    }

    private String describeAddress() {
        if (!m_tcpConfig.isEnabled()) {
            return "disabled";
        }
        final String address = m_tcpConfig.getListenAddress() == null ? "0.0.0.0" : m_tcpConfig.getListenAddress();
        return address + ":" + m_tcpConfig.getPort();
    }
}
