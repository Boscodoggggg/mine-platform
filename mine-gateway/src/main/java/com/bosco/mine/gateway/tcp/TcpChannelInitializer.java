package com.bosco.mine.gateway.tcp;

import com.bosco.mine.gateway.publish.TelemetryPublisher;
import com.bosco.mine.gateway.configuration.ProtocolSnapshotRegistry;
import com.bosco.mine.gateway.tcp.login.TcpLoginAuthenticator;
import com.bosco.mine.gateway.tcp.login.TcpLoginFrameCodec;
import com.bosco.mine.protocol.engine.tcp.TcpProtocolPluginRegistry;
import com.bosco.mine.protocol.sdk.tcp.TcpFrameDefinition;
import com.bosco.mine.protocol.sdk.tcp.TcpProtocolPlugin;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.EventExecutorGroup;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 按插件受控帧定义为每个 TCP 连接创建隔离的 Netty pipeline。
 */
public final class TcpChannelInitializer extends ChannelInitializer<Channel> {

    private final TcpProtocolPlugin plugin;
    private final TcpMessageNormalizer normalizer;
    private final TelemetryPublisher publisher;
    private final int readIdleSeconds;
    private final SslContext sslContext;
    private final TcpConnectionLimiter connectionLimiter;
    private final EventExecutorGroup businessExecutor;
    private final ChannelGroup channels;
    private final TcpGatewayObserver observer;
    private final TcpLoginAuthenticator loginAuthenticator;
    private final ProtocolSnapshotRegistry protocolSnapshots;
    private final TcpProtocolPluginRegistry pluginRegistry;

    /**
     * 创建 TCP pipeline 初始化器。
     *
     * @param plugin 当前监听端口绑定的插件
     * @param normalizer TCP 消息标准化器
     * @param publisher 可靠遥测发布器
     * @param readIdleSeconds 无入站字节后关闭连接的秒数
     */
    public TcpChannelInitializer(
            TcpProtocolPlugin plugin,
            TcpMessageNormalizer normalizer,
            TelemetryPublisher publisher,
            int readIdleSeconds
    ) {
        this(plugin, normalizer, publisher, readIdleSeconds,
                null, null, null, null, TcpGatewayObserver.NOOP);
    }

    /**
     * 创建生产监听器使用的完整 pipeline 初始化器。
     *
     * @param plugin 当前监听端口绑定的插件
     * @param normalizer TCP 消息标准化器
     * @param publisher 可靠遥测发布器
     * @param readIdleSeconds 无入站字节后关闭连接的秒数
     * @param sslContext 可选 TLS 服务端上下文
     * @param connectionLimiter 全局和单 IP 连接配额
     * @param businessExecutor 阻塞 publisher confirm 专用有序执行器
     * @param channels 供优雅停止关闭的活动连接组
     * @param observer 固定低基数运行事件观察器
     */
    public TcpChannelInitializer(
            TcpProtocolPlugin plugin,
            TcpMessageNormalizer normalizer,
            TelemetryPublisher publisher,
            int readIdleSeconds,
            SslContext sslContext,
            TcpConnectionLimiter connectionLimiter,
            EventExecutorGroup businessExecutor,
            ChannelGroup channels,
            TcpGatewayObserver observer
    ) {
        this.plugin = Objects.requireNonNull(plugin, "TCP 协议插件不能为空");
        this.normalizer = Objects.requireNonNull(normalizer, "TCP 消息标准化器不能为空");
        this.publisher = Objects.requireNonNull(publisher, "遥测发布器不能为空");
        if (readIdleSeconds < 1 || readIdleSeconds > 86400) {
            throw new IllegalArgumentException("TCP 读空闲秒数必须在 1 到 86400 之间");
        }
        this.readIdleSeconds = readIdleSeconds;
        this.sslContext = sslContext;
        this.connectionLimiter = connectionLimiter;
        this.businessExecutor = businessExecutor;
        this.channels = channels;
        this.observer = Objects.requireNonNull(observer, "TCP 运行观察器不能为空");
        this.loginAuthenticator = null;
        this.protocolSnapshots = null;
        this.pluginRegistry = null;
    }

    /**
     * 创建统一 TCP 入口使用的登录后产品路由 pipeline 初始化器。
     *
     * @param loginAuthenticator TCP 登录认证器
     * @param protocolSnapshots 产品协议快照
     * @param pluginRegistry TCP 插件注册表
     * @param normalizer TCP 消息标准化器
     * @param publisher 可靠遥测发布器
     * @param readIdleSeconds 无入站字节后关闭连接的秒数
     * @param sslContext TLS 服务端上下文
     * @param connectionLimiter 全局和单 IP 连接配额
     * @param businessExecutor BCrypt、解析和发布专用有序执行器
     * @param channels 活动连接组
     * @param observer 固定低基数运行事件观察器
     */
    public TcpChannelInitializer(
            TcpLoginAuthenticator loginAuthenticator,
            ProtocolSnapshotRegistry protocolSnapshots,
            TcpProtocolPluginRegistry pluginRegistry,
            TcpMessageNormalizer normalizer,
            TelemetryPublisher publisher,
            int readIdleSeconds,
            SslContext sslContext,
            TcpConnectionLimiter connectionLimiter,
            EventExecutorGroup businessExecutor,
            ChannelGroup channels,
            TcpGatewayObserver observer
    ) {
        this.plugin = null;
        this.loginAuthenticator = Objects.requireNonNull(loginAuthenticator, "TCP 登录认证器不能为空");
        this.protocolSnapshots = Objects.requireNonNull(protocolSnapshots, "产品协议快照不能为空");
        this.pluginRegistry = Objects.requireNonNull(pluginRegistry, "TCP 插件注册表不能为空");
        this.normalizer = Objects.requireNonNull(normalizer, "TCP 消息标准化器不能为空");
        this.publisher = Objects.requireNonNull(publisher, "遥测发布器不能为空");
        if (readIdleSeconds < 1 || readIdleSeconds > 86400) {
            throw new IllegalArgumentException("TCP 读空闲秒数必须在 1 到 86400 之间");
        }
        this.readIdleSeconds = readIdleSeconds;
        this.sslContext = Objects.requireNonNull(sslContext, "统一 TCP 入口必须启用 TLS");
        this.connectionLimiter = connectionLimiter;
        this.businessExecutor = Objects.requireNonNull(businessExecutor, "统一 TCP 业务执行器不能为空");
        this.channels = channels;
        this.observer = Objects.requireNonNull(observer, "TCP 运行观察器不能为空");
    }

    @Override
    protected void initChannel(Channel channel) {
        if (channels != null) channels.add(channel);
        if (!admit(channel)) return;
        if (sslContext != null) {
            channel.pipeline().addLast("tcpTls", sslContext.newHandler(channel.alloc()));
        }
        channel.pipeline().addLast("tcpReadIdle", new IdleStateHandler(
                readIdleSeconds, 0, 0, TimeUnit.SECONDS));
        if (plugin != null) {
            TcpFrameDefinition frame = plugin.frameDefinition();
            channel.pipeline().addLast("tcpFrameDecoder", new LengthFieldBasedFrameDecoder(
                    frame.byteOrder(), frame.maxFrameLength(), frame.lengthFieldOffset(),
                    frame.lengthFieldLength(), frame.lengthAdjustment(),
                    frame.initialBytesToStrip(), true));
            TcpDeviceChannelHandler handler = new TcpDeviceChannelHandler(
                    plugin, normalizer, publisher, observer);
            if (businessExecutor == null) channel.pipeline().addLast("tcpDeviceHandler", handler);
            else channel.pipeline().addLast(businessExecutor, "tcpDeviceHandler", handler);
            return;
        }
        channel.pipeline().addLast("tcpLoginFrameDecoder", new LengthFieldBasedFrameDecoder(
                TcpLoginFrameCodec.MAX_FRAME_LENGTH,
                TcpLoginFrameCodec.LENGTH_FIELD_OFFSET,
                TcpLoginFrameCodec.LENGTH_FIELD_LENGTH,
                -7,
                0,
                true));
        channel.pipeline().addLast(businessExecutor, "tcpProductBinding", new ProductBoundTcpChannelHandler(
                new TcpLoginFrameCodec(), loginAuthenticator, protocolSnapshots, pluginRegistry,
                normalizer, publisher, businessExecutor, observer));
    }

    private boolean admit(Channel channel) {
        if (connectionLimiter == null) return true;
        InetAddress address = channel.remoteAddress() instanceof InetSocketAddress remote
                ? remote.getAddress() : InetAddress.getLoopbackAddress();
        Optional<TcpConnectionLimiter.Lease> lease = connectionLimiter.tryAcquire(address);
        if (lease.isEmpty()) {
            observer.connectionRejected();
            channel.close();
            return false;
        }
        observer.connectionAccepted();
        channel.pipeline().addLast("tcpConnectionLease",
                new ConnectionLeaseHandler(lease.orElseThrow(), observer));
        return true;
    }

    /**
     * 在连接失活时准确释放配额并更新活动连接指标。
     */
    private static final class ConnectionLeaseHandler extends ChannelInboundHandlerAdapter {

        private final TcpConnectionLimiter.Lease lease;
        private final TcpGatewayObserver observer;

        private ConnectionLeaseHandler(
                TcpConnectionLimiter.Lease lease,
                TcpGatewayObserver observer
        ) {
            this.lease = lease;
            this.observer = observer;
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) throws Exception {
            lease.close();
            observer.connectionClosed();
            super.channelInactive(context);
        }
    }
}
