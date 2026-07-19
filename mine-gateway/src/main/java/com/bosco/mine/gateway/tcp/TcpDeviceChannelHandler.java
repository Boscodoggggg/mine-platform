package com.bosco.mine.gateway.tcp;

import com.bosco.mine.gateway.normalize.InvalidDeviceMessageException;
import com.bosco.mine.gateway.publish.TelemetryPublisher;
import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.model.message.UnifiedDeviceMessage;
import com.bosco.mine.protocol.sdk.tcp.TcpDecodedMessage;
import com.bosco.mine.protocol.sdk.tcp.TcpProtocolException;
import com.bosco.mine.protocol.sdk.tcp.TcpProtocolPlugin;
import com.bosco.mine.protocol.sdk.tcp.TcpProtocolSession;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * 串行处理一个 TCP 连接的完整帧，并在可靠发布完成后发送应用级确认。
 */
public final class TcpDeviceChannelHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private static final Logger LOGGER = LoggerFactory.getLogger(TcpDeviceChannelHandler.class);

    private final String protocolKey;
    private final String protocolVersion;
    private final TcpProtocolSession session;
    private final TcpMessageNormalizer normalizer;
    private final TelemetryPublisher publisher;
    private final TcpGatewayObserver observer;

    private String boundDeviceId;

    /**
     * 为一个新连接创建独立协议会话处理器。
     *
     * @param plugin 当前监听端口绑定的离线审核插件
     * @param normalizer TCP 可信身份和统一消息转换器
     * @param publisher 可靠平台事件发布器
     */
    public TcpDeviceChannelHandler(
            TcpProtocolPlugin plugin,
            TcpMessageNormalizer normalizer,
            TelemetryPublisher publisher
    ) {
        this(plugin, normalizer, publisher, TcpGatewayObserver.NOOP);
    }

    /**
     * 为一个新连接创建带运行观察器的独立协议会话处理器。
     *
     * @param plugin 当前监听端口绑定的离线审核插件
     * @param normalizer TCP 可信身份和统一消息转换器
     * @param publisher 可靠平台事件发布器
     * @param observer 固定低基数运行事件观察器
     */
    public TcpDeviceChannelHandler(
            TcpProtocolPlugin plugin,
            TcpMessageNormalizer normalizer,
            TelemetryPublisher publisher,
            TcpGatewayObserver observer
    ) {
        this(plugin, normalizer, publisher, observer, null);
    }

    /**
     * 为已经完成统一登录的连接创建绑定到指定设备的独立协议会话。
     *
     * @param plugin 产品绑定的离线审核插件
     * @param normalizer TCP 消息标准化器
     * @param publisher 可靠平台事件发布器
     * @param observer 固定低基数运行事件观察器
     * @param boundDeviceId 登录阶段确认的平台设备标识
     */
    public TcpDeviceChannelHandler(
            TcpProtocolPlugin plugin,
            TcpMessageNormalizer normalizer,
            TelemetryPublisher publisher,
            TcpGatewayObserver observer,
            String boundDeviceId
    ) {
        Objects.requireNonNull(plugin, "TCP 协议插件不能为空");
        this.protocolKey = plugin.protocolKey();
        this.protocolVersion = plugin.protocolVersion();
        this.session = Objects.requireNonNull(plugin.openSession(), "TCP 插件会话不能为空");
        this.normalizer = Objects.requireNonNull(normalizer, "TCP 消息标准化器不能为空");
        this.publisher = Objects.requireNonNull(publisher, "遥测发布器不能为空");
        this.observer = Objects.requireNonNull(observer, "TCP 运行观察器不能为空");
        this.boundDeviceId = boundDeviceId;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, ByteBuf input) {
        byte[] frame = new byte[input.readableBytes()];
        input.getBytes(input.readerIndex(), frame);
        try {
            TcpDecodedMessage decoded = session.decode(frame);
            MessageEnvelope<UnifiedDeviceMessage> envelope = normalizer.normalize(
                    decoded, protocolVersion);
            String deviceId = envelope.payload().deviceId();
            if (boundDeviceId != null && !boundDeviceId.equals(deviceId)) {
                LOGGER.warn("关闭发生设备身份切换的 TCP 连接，protocolKey={}", protocolKey);
                observer.frameRejected("IDENTITY_SWITCH");
                context.close();
                return;
            }
            if (boundDeviceId == null) boundDeviceId = deviceId;

            publisher.publish(envelope);
            byte[] acknowledgement = session.encodeAcknowledgement(decoded);
            context.writeAndFlush(Unpooled.wrappedBuffer(acknowledgement));
            observer.frameSucceeded();
            observer.acknowledgementSent();
        } catch (TcpProtocolException exception) {
            LOGGER.warn("拒绝 TCP 协议帧，protocolKey={}, reason={}",
                    protocolKey, exception.errorCode());
            observer.frameRejected(exception.errorCode().name());
            context.close();
        } catch (InvalidDeviceMessageException | IllegalArgumentException exception) {
            LOGGER.warn("拒绝未通过可信身份或统一消息校验的 TCP 帧，protocolKey={}", protocolKey);
            observer.frameRejected("IDENTITY_OR_MESSAGE");
            context.close();
        } catch (RuntimeException exception) {
            LOGGER.error("TCP 遥测可靠发布或确认编码失败，protocolKey={}, exceptionType={}",
                    protocolKey, exception.getClass().getSimpleName());
            observer.publishFailed();
            observer.frameRejected("PUBLISH_OR_ACK");
            context.close();
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext context, Object event) throws Exception {
        if (event instanceof IdleStateEvent) {
            LOGGER.info("关闭读空闲 TCP 连接，protocolKey={}", protocolKey);
            context.close();
            return;
        }
        super.userEventTriggered(context, event);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        LOGGER.warn("关闭 TCP pipeline 异常连接，protocolKey={}, exceptionType={}",
                protocolKey, cause.getClass().getSimpleName());
        observer.frameRejected("PIPELINE");
        context.close();
    }
}
