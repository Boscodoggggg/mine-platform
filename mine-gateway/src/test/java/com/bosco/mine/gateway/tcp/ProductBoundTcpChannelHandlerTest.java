package com.bosco.mine.gateway.tcp;

import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.example.protocol.haultruck.HaulTruckProtocolPlugin;
import com.bosco.mine.gateway.configuration.ConfigurationSnapshot;
import com.bosco.mine.gateway.configuration.ProtocolSnapshotRegistry;
import com.bosco.mine.gateway.session.DeviceSessionRegistry;
import com.bosco.mine.gateway.tcp.login.TcpLoginAuthenticator;
import com.bosco.mine.gateway.tcp.login.TcpLoginFrame;
import com.bosco.mine.gateway.tcp.login.TcpLoginFrameCodec;
import com.bosco.mine.protocol.engine.snapshot.ProtocolSnapshotHasher;
import com.bosco.mine.protocol.engine.tcp.TcpProtocolPluginRegistry;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验统一 TCP 登录后按产品快照选择插件并切换连接 pipeline。
 */
class ProductBoundTcpChannelHandlerTest {

    @Test
    void 登录成功后应安装产品绑定插件的拆帧器和独立会话处理器() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        DeviceSessionRegistry identities = identities(encoder);
        ProtocolSnapshotRegistry protocols = protocols();
        TcpLoginFrameCodec codec = new TcpLoginFrameCodec();
        ProductBoundTcpChannelHandler handler = new ProductBoundTcpChannelHandler(
                codec,
                new TcpLoginAuthenticator(identities, encoder),
                protocols,
                new TcpProtocolPluginRegistry(List.of(new HaulTruckProtocolPlugin())),
                new TcpMessageNormalizer(identities, IdGenerator.timeOrdered(), Clock.systemUTC()),
                envelope -> { }
        );
        EmbeddedChannel channel = new EmbeddedChannel(
                new LengthFieldBasedFrameDecoder(
                        TcpLoginFrameCodec.MAX_FRAME_LENGTH,
                        TcpLoginFrameCodec.LENGTH_FIELD_OFFSET,
                        TcpLoginFrameCodec.LENGTH_FIELD_LENGTH,
                        -7,
                        0),
                handler
        );

        channel.writeInbound(Unpooled.wrappedBuffer(codec.encode(new TcpLoginFrame(
                "haul-truck", "truck-001", "tcp-truck-001", "correct-secret"))));

        assertThat(channel.isActive()).isTrue();
        assertThat(channel.pipeline().get("tcpFrameDecoder")).isInstanceOf(LengthFieldBasedFrameDecoder.class);
        assertThat(channel.pipeline().get("tcpDeviceHandler")).isInstanceOf(TcpDeviceChannelHandler.class);
        assertThat(channel.pipeline().context(handler)).isNull();
        channel.finishAndReleaseAll();
    }

    @Test
    void 产品未绑定可信TCP插件时应关闭连接() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        DeviceSessionRegistry identities = identities(encoder);
        TcpLoginFrameCodec codec = new TcpLoginFrameCodec();
        EmbeddedChannel channel = new EmbeddedChannel(new ProductBoundTcpChannelHandler(
                codec,
                new TcpLoginAuthenticator(identities, encoder),
                new ProtocolSnapshotRegistry(true),
                new TcpProtocolPluginRegistry(List.of(new HaulTruckProtocolPlugin())),
                new TcpMessageNormalizer(identities, IdGenerator.timeOrdered(), Clock.systemUTC()),
                envelope -> { }
        ));

        channel.writeInbound(Unpooled.wrappedBuffer(codec.encode(new TcpLoginFrame(
                "haul-truck", "truck-001", "tcp-truck-001", "correct-secret"))));

        assertThat(channel.isActive()).isFalse();
        channel.finishAndReleaseAll();
    }

    private DeviceSessionRegistry identities(BCryptPasswordEncoder encoder) {
        DeviceSessionRegistry registry = new DeviceSessionRegistry();
        registry.activate(1, "a".repeat(64), List.of(new DeviceSessionRegistry.DeviceIdentity(
                "haul-truck", "mqtt-truck-001", "019f63e2-071f-706a-bd12-4dad7150e75d",
                "truck-001", "mine-site-001", "haul-truck-binary:v1",
                "tcp-truck-001", encoder.encode("correct-secret")
        )));
        return registry;
    }

    private ProtocolSnapshotRegistry protocols() {
        ProtocolSnapshotRegistry registry = new ProtocolSnapshotRegistry(true);
        UUID productId = UUID.randomUUID();
        String snapshot = """
                {"schemaVersion":2,"productId":"%s","productKey":"haul-truck",
                 "modelVersionId":"%s","protocolMappingVersionId":"%s",
                 "binding":{"transport":"TCP","payloadFormat":"BINARY",
                 "routingMode":"TRUSTED_IDENTITY","parserType":"JAVA_PLUGIN",
                 "pluginKey":"haul-truck-binary","pluginVersion":"haul-truck-binary:v1"}}
                """.formatted(productId, UUID.randomUUID(), UUID.randomUUID());
        String hash = new ProtocolSnapshotHasher().sha256(snapshot);
        registry.activate(new ConfigurationSnapshot(
                UUID.randomUUID(), productId, UUID.randomUUID(), 1, hash, snapshot));
        return registry;
    }
}
