package com.bosco.mine.gateway.tcp;

import com.bosco.mine.gateway.bootstrap.GatewayTcpProperties;
import com.bosco.mine.gateway.publish.TelemetryPublisher;
import com.bosco.mine.protocol.engine.tcp.TcpProtocolPluginRegistry;
import com.bosco.mine.protocol.sdk.tcp.TcpProtocolPlugin;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 管理单个受控 TCP 二进制监听端口的 Netty 生命周期。
 */
public final class TcpGatewayServer implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(TcpGatewayServer.class);

    private final GatewayTcpProperties properties;
    private final TcpProtocolPluginRegistry pluginRegistry;
    private final TcpMessageNormalizer normalizer;
    private final TelemetryPublisher publisher;
    private final TcpGatewayMetrics metrics;

    private volatile boolean running;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private EventExecutorGroup businessGroup;
    private ChannelGroup childChannels;
    private Channel serverChannel;

    /**
     * 创建尚未启动的 TCP 监听器。
     *
     * @param properties TCP 监听和资源参数
     * @param pluginRegistry 已完整校验的插件注册表
     * @param normalizer TCP 可信身份标准化器
     * @param publisher 可靠遥测发布器
     * @param meterRegistry Micrometer 注册表
     */
    public TcpGatewayServer(
            GatewayTcpProperties properties,
            TcpProtocolPluginRegistry pluginRegistry,
            TcpMessageNormalizer normalizer,
            TelemetryPublisher publisher,
            MeterRegistry meterRegistry
    ) {
        this.properties = Objects.requireNonNull(properties, "TCP 参数不能为空");
        this.pluginRegistry = Objects.requireNonNull(pluginRegistry, "插件注册表不能为空");
        this.normalizer = Objects.requireNonNull(normalizer, "TCP 标准化器不能为空");
        this.publisher = Objects.requireNonNull(publisher, "遥测发布器不能为空");
        this.metrics = new TcpGatewayMetrics(
                Objects.requireNonNull(meterRegistry, "指标注册表不能为空"),
                requireProtocolKey(properties));
    }

    @Override
    public synchronized void start() {
        if (running) return;
        properties.validate();
        if (!properties.resolvedEnabled()) {
            throw new IllegalStateException("TCP 监听器未启用");
        }
        TcpProtocolPlugin plugin = pluginRegistry.require(properties.resolvedProtocolKey());
        TcpConnectionLimiter limiter = new TcpConnectionLimiter(
                properties.resolvedMaxConnections(), properties.resolvedMaxConnectionsPerIp());
        SslContext sslContext = sslContext(properties.resolvedTls());
        childChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
        bossGroup = new MultiThreadIoEventLoopGroup(
                1, new DefaultThreadFactory("mine-tcp-boss", true), NioIoHandler.newFactory());
        workerGroup = new MultiThreadIoEventLoopGroup(
                properties.resolvedWorkerThreads(),
                new DefaultThreadFactory("mine-tcp-io", true), NioIoHandler.newFactory());
        businessGroup = new DefaultEventExecutorGroup(
                properties.resolvedBusinessThreads(),
                new DefaultThreadFactory("mine-tcp-business", true));
        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .option(ChannelOption.SO_BACKLOG, properties.resolvedBacklog())
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new TcpChannelInitializer(
                            plugin, normalizer, publisher, properties.resolvedReadIdleSeconds(),
                            sslContext, limiter, businessGroup, childChannels, metrics));
            serverChannel = bootstrap.bind(
                            properties.resolvedBindHost(), properties.resolvedPort())
                    .syncUninterruptibly().channel();
            running = true;
            metrics.listenerUp(true);
            LOGGER.info("TCP 监听器已启动，protocolKey={}, bindHost={}, port={}, tls={}",
                    properties.resolvedProtocolKey(), properties.resolvedBindHost(),
                    localPort(), properties.resolvedTls().resolvedEnabled());
        } catch (RuntimeException exception) {
            metrics.listenerUp(false);
            closeResources();
            throw new IllegalStateException("TCP 监听器启动失败", exception);
        }
    }

    @Override
    public synchronized void stop() {
        if (!running && bossGroup == null && workerGroup == null && businessGroup == null) return;
        metrics.listenerUp(false);
        running = false;
        closeResources();
        LOGGER.info("TCP 监听器已停止，protocolKey={}", properties.resolvedProtocolKey());
    }

    @Override
    public void stop(Runnable callback) {
        try {
            stop();
        } finally {
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    /**
     * 返回实际绑定端口，允许自动化使用端口 0 获取随机端口。
     *
     * @return 当前监听端口
     */
    public synchronized int localPort() {
        if (serverChannel == null || !(serverChannel.localAddress() instanceof InetSocketAddress address)) {
            throw new IllegalStateException("TCP 监听器尚未成功绑定");
        }
        return address.getPort();
    }

    private void closeResources() {
        Duration timeout = properties.resolvedShutdownTimeout();
        long timeoutMillis = timeout.toMillis();
        if (serverChannel != null) {
            serverChannel.close().awaitUninterruptibly(timeoutMillis);
            serverChannel = null;
        }
        if (childChannels != null) {
            childChannels.close().awaitUninterruptibly(timeoutMillis);
            childChannels = null;
        }
        shutdown(businessGroup, timeout);
        shutdown(workerGroup, timeout);
        shutdown(bossGroup, timeout);
        businessGroup = null;
        workerGroup = null;
        bossGroup = null;
    }

    private void shutdown(EventExecutorGroup group, Duration timeout) {
        if (group == null) return;
        group.shutdownGracefully(0, timeout.toMillis(), TimeUnit.MILLISECONDS)
                .awaitUninterruptibly(timeout.toMillis());
    }

    private SslContext sslContext(GatewayTcpProperties.Tls tls) {
        if (!tls.resolvedEnabled()) return null;
        try {
            return SslContextBuilder.forServer(
                    tls.certificate().toFile(), tls.privateKey().toFile()).build();
        } catch (SSLException exception) {
            throw new IllegalArgumentException("无法加载 TCP TLS 证书或私钥", exception);
        }
    }

    private static String requireProtocolKey(GatewayTcpProperties properties) {
        String protocolKey = Objects.requireNonNull(properties, "TCP 参数不能为空")
                .resolvedProtocolKey();
        return protocolKey == null || protocolKey.isBlank() ? "unconfigured" : protocolKey;
    }
}
