package com.bosco.mine.gateway.tcp;

import com.bosco.mine.gateway.publish.TelemetryPublisher;
import com.bosco.mine.protocol.sdk.tcp.TcpFrameDefinition;
import com.bosco.mine.protocol.sdk.tcp.TcpProtocolPlugin;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 按插件受控帧定义为每个 TCP 连接创建隔离的 Netty pipeline。
 */
public final class TcpChannelInitializer extends ChannelInitializer<Channel> {

    private final TcpProtocolPlugin plugin;
    private final TcpMessageNormalizer normalizer;
    private final TelemetryPublisher publisher;
    private final int readIdleSeconds;

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
        this.plugin = Objects.requireNonNull(plugin, "TCP 协议插件不能为空");
        this.normalizer = Objects.requireNonNull(normalizer, "TCP 消息标准化器不能为空");
        this.publisher = Objects.requireNonNull(publisher, "遥测发布器不能为空");
        if (readIdleSeconds < 1 || readIdleSeconds > 86400) {
            throw new IllegalArgumentException("TCP 读空闲秒数必须在 1 到 86400 之间");
        }
        this.readIdleSeconds = readIdleSeconds;
    }

    @Override
    protected void initChannel(Channel channel) {
        TcpFrameDefinition frame = plugin.frameDefinition();
        channel.pipeline().addLast("tcpReadIdle", new IdleStateHandler(
                readIdleSeconds, 0, 0, TimeUnit.SECONDS));
        channel.pipeline().addLast("tcpFrameDecoder", new LengthFieldBasedFrameDecoder(
                frame.byteOrder(), frame.maxFrameLength(), frame.lengthFieldOffset(),
                frame.lengthFieldLength(), frame.lengthAdjustment(),
                frame.initialBytesToStrip(), true));
        channel.pipeline().addLast("tcpDeviceHandler", new TcpDeviceChannelHandler(
                plugin, normalizer, publisher));
    }
}
