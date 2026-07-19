package com.bosco.mine.gateway.tcp;

import com.bosco.mine.gateway.configuration.ProtocolSnapshotRegistry;
import com.bosco.mine.gateway.publish.TelemetryPublisher;
import com.bosco.mine.gateway.session.DeviceSessionRegistry;
import com.bosco.mine.gateway.tcp.login.TcpLoginAuthenticator;
import com.bosco.mine.gateway.tcp.login.TcpLoginFrame;
import com.bosco.mine.gateway.tcp.login.TcpLoginFrameCodec;
import com.bosco.mine.protocol.engine.binding.ProductProtocolBinding;
import com.bosco.mine.protocol.engine.tcp.TcpProtocolPluginRegistry;
import com.bosco.mine.protocol.sdk.tcp.TcpFrameDefinition;
import com.bosco.mine.protocol.sdk.tcp.TcpProtocolPlugin;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.util.concurrent.EventExecutorGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * 在统一 TCP 连接首帧认证后按产品快照选择插件并原子切换 pipeline。
 */
public final class ProductBoundTcpChannelHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProductBoundTcpChannelHandler.class);
    private static final byte[] LOGIN_ACK = {0x4d, 0x4e, 0x54, 0x4c, 0x01, 0x00};

    private final TcpLoginFrameCodec codec;
    private final TcpLoginAuthenticator authenticator;
    private final ProtocolSnapshotRegistry protocolSnapshots;
    private final TcpProtocolPluginRegistry plugins;
    private final TcpMessageNormalizer normalizer;
    private final TelemetryPublisher publisher;
    private final EventExecutorGroup businessExecutor;
    private final TcpGatewayObserver observer;

    /**
     * 创建无需指定执行器和指标观察器的统一登录处理器。
     */
    public ProductBoundTcpChannelHandler(
            TcpLoginFrameCodec codec,
            TcpLoginAuthenticator authenticator,
            ProtocolSnapshotRegistry protocolSnapshots,
            TcpProtocolPluginRegistry plugins,
            TcpMessageNormalizer normalizer,
            TelemetryPublisher publisher
    ) {
        this(codec, authenticator, protocolSnapshots, plugins, normalizer, publisher,
                null, TcpGatewayObserver.NOOP);
    }

    /**
     * 创建生产统一入口使用的登录和动态插件路由处理器。
     */
    public ProductBoundTcpChannelHandler(
            TcpLoginFrameCodec codec,
            TcpLoginAuthenticator authenticator,
            ProtocolSnapshotRegistry protocolSnapshots,
            TcpProtocolPluginRegistry plugins,
            TcpMessageNormalizer normalizer,
            TelemetryPublisher publisher,
            EventExecutorGroup businessExecutor,
            TcpGatewayObserver observer
    ) {
        this.codec = Objects.requireNonNull(codec, "登录帧编解码器不能为空");
        this.authenticator = Objects.requireNonNull(authenticator, "登录认证器不能为空");
        this.protocolSnapshots = Objects.requireNonNull(protocolSnapshots, "产品协议快照不能为空");
        this.plugins = Objects.requireNonNull(plugins, "TCP 插件注册表不能为空");
        this.normalizer = Objects.requireNonNull(normalizer, "TCP 标准化器不能为空");
        this.publisher = Objects.requireNonNull(publisher, "遥测发布器不能为空");
        this.businessExecutor = businessExecutor;
        this.observer = Objects.requireNonNull(observer, "TCP 运行观察器不能为空");
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, ByteBuf input) {
        byte[] bytes = new byte[input.readableBytes()];
        input.getBytes(input.readerIndex(), bytes);
        try {
            TcpLoginFrame login = codec.decode(bytes);
            DeviceSessionRegistry.DeviceIdentity identity = authenticator.authenticate(login);
            ProtocolSnapshotRegistry.ActiveProtocol active = protocolSnapshots.require(identity.productKey());
            ProductProtocolBinding binding = active.binding();
            if (binding.transport() != ProductProtocolBinding.Transport.TCP
                    || binding.routingMode() != ProductProtocolBinding.RoutingMode.TRUSTED_IDENTITY
                    || !binding.usesJavaPlugin()) {
                throw new IllegalArgumentException("产品未绑定统一 TCP 插件");
            }
            TcpProtocolPlugin plugin = plugins.require(binding.pluginKey());
            if (!binding.pluginVersion().equals(plugin.protocolVersion())
                    || !identity.protocolVersion().equals(plugin.protocolVersion())) {
                throw new IllegalArgumentException("TCP 插件版本与产品或设备身份不一致");
            }
            switchPipeline(context, plugin, identity.deviceId());
            context.writeAndFlush(Unpooled.wrappedBuffer(LOGIN_ACK.clone()));
        } catch (RuntimeException exception) {
            LOGGER.warn("统一 TCP 登录或产品插件路由失败，reason={}",
                    exception.getClass().getSimpleName());
            observer.frameRejected("LOGIN_OR_BINDING");
            context.close();
        }
    }

    private void switchPipeline(
            ChannelHandlerContext context,
            TcpProtocolPlugin plugin,
            String deviceId
    ) {
        ChannelPipeline pipeline = context.pipeline();
        if (pipeline.context(LengthFieldBasedFrameDecoder.class) != null) {
            pipeline.remove(LengthFieldBasedFrameDecoder.class);
        }
        TcpFrameDefinition frame = plugin.frameDefinition();
        pipeline.addBefore(context.name(), "tcpFrameDecoder", new LengthFieldBasedFrameDecoder(
                frame.byteOrder(), frame.maxFrameLength(), frame.lengthFieldOffset(),
                frame.lengthFieldLength(), frame.lengthAdjustment(), frame.initialBytesToStrip(), true));
        TcpDeviceChannelHandler deviceHandler = new TcpDeviceChannelHandler(
                plugin, normalizer, publisher, observer, deviceId);
        if (businessExecutor == null) {
            pipeline.addAfter(context.name(), "tcpDeviceHandler", deviceHandler);
        } else {
            pipeline.addAfter(businessExecutor, context.name(), "tcpDeviceHandler", deviceHandler);
        }
        pipeline.remove(this);
    }
}
