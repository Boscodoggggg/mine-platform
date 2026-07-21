package com.bosco.mine.gateway.tcp;

import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.example.protocol.haultruck.HaulTruckProtocolPlugin;
import com.bosco.mine.gateway.bootstrap.GatewayTcpProperties;
import com.bosco.mine.gateway.session.DeviceSessionRegistry;
import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.model.message.UnifiedDeviceMessage;
import com.bosco.mine.protocol.engine.tcp.TcpProtocolPluginRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用本机随机端口验证 TCP 监听、可靠发布、确认和优雅停止完整生命周期。
 */
class TcpGatewayServerTest {

    private static final byte[] GOLDEN = HexFormat.of().parseHex("""
            4d500101003309747275636b2d3030310d626f6f742d3230323630373137
            0000002a000000006a597f0b000546007d01c8254a
            """.replaceAll("\\s", ""));

    @Test
    void 应在随机端口接收半包并从业务线程发布后返回ACK() throws Exception {
        GatewayTcpProperties properties = properties();
        DeviceSessionRegistry identities = identities();
        ArrayBlockingQueue<MessageEnvelope<UnifiedDeviceMessage>> published = new ArrayBlockingQueue<>(1);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        TcpGatewayServer server = new TcpGatewayServer(
                properties,
                new TcpProtocolPluginRegistry(List.of(new HaulTruckProtocolPlugin())),
                new TcpMessageNormalizer(identities, IdGenerator.timeOrdered(), Clock.systemUTC()),
                envelope -> {
                    assertThat(Thread.currentThread().getName()).contains("mine-tcp-business");
                    published.add(envelope);
                },
                meters
        );

        server.start();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", server.localPort()), 3000);
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(GOLDEN, 0, 20);
            socket.getOutputStream().write(GOLDEN, 20, GOLDEN.length - 20);
            socket.getOutputStream().flush();

            byte[] acknowledgement = socket.getInputStream().readNBytes(13);
            MessageEnvelope<UnifiedDeviceMessage> envelope = published.poll(3, TimeUnit.SECONDS);

            assertThat(acknowledgement).hasSize(13);
            assertThat(envelope).isNotNull();
            assertThat(envelope.payload().deviceId())
                    .isEqualTo("019f63e2-071f-706a-bd12-4dad7150e75d");
            assertThat(server.isRunning()).isTrue();
            assertThat(meters.get("mine.gateway.tcp.listener.up").gauge().value()).isEqualTo(1);
        } finally {
            server.stop();
        }

        assertThat(server.isRunning()).isFalse();
        assertThat(meters.get("mine.gateway.tcp.listener.up").gauge().value()).isZero();
    }

    @Test
    void 启用TLS时应完成握手并只在可靠发布后返回ACK() throws Exception {
        SelfSignedCertificate certificate = new SelfSignedCertificate("localhost");
        ArrayBlockingQueue<MessageEnvelope<UnifiedDeviceMessage>> published = new ArrayBlockingQueue<>(1);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        GatewayTcpProperties properties = new GatewayTcpProperties(
                true, "127.0.0.1", 0, "haul-truck-binary",
                1, 1, 64, 10, 2, 5, Duration.ofSeconds(5),
                new GatewayTcpProperties.Tls(
                        true, certificate.certificate().toPath(), certificate.privateKey().toPath())
        );
        TcpGatewayServer server = new TcpGatewayServer(
                properties,
                new TcpProtocolPluginRegistry(List.of(new HaulTruckProtocolPlugin())),
                new TcpMessageNormalizer(identities(), IdGenerator.timeOrdered(), Clock.systemUTC()),
                published::add,
                meters
        );

        try {
            server.start();
            SSLContext clientContext = trustAllClientContext();
            try (SSLSocket socket = (SSLSocket) clientContext.getSocketFactory()
                    .createSocket("127.0.0.1", server.localPort())) {
                socket.setSoTimeout(3000);
                socket.startHandshake();
                socket.getOutputStream().write(GOLDEN);
                socket.getOutputStream().flush();

                assertThat(socket.getInputStream().readNBytes(13)).hasSize(13);
                assertThat(published.poll(3, TimeUnit.SECONDS)).isNotNull();
                assertThat(meters.get("mine.gateway.tcp.connections")
                        .tag("protocol", "haul-truck-binary")
                        .tag("result", "accepted").counter().count()).isEqualTo(1);
                assertThat(meters.get("mine.gateway.tcp.acknowledgements")
                        .tag("protocol", "haul-truck-binary")
                        .tag("result", "success").counter().count()).isEqualTo(1);
            }
        } finally {
            server.stop();
            certificate.delete();
        }
    }

    private GatewayTcpProperties properties() {
        GatewayTcpProperties properties = new GatewayTcpProperties(
                true, "127.0.0.1", 0, "haul-truck-binary",
                1, 1, 64, 10, 2, 5, Duration.ofSeconds(5),
                new GatewayTcpProperties.Tls(false, null, null)
        );
        properties.validate();
        return properties;
    }

    private DeviceSessionRegistry identities() {
        DeviceSessionRegistry registry = new DeviceSessionRegistry();
        registry.activate(1, "a".repeat(64), List.of(new DeviceSessionRegistry.DeviceIdentity(
                "haul-truck", "tcp-001", "019f63e2-071f-706a-bd12-4dad7150e75d",
                "truck-001", "mine-site-001", "haul-truck-binary:v1"
        )));
        return registry;
    }

    private SSLContext trustAllClientContext() throws Exception {
        X509TrustManager trustManager = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authenticationType) {
                // 自动化客户端仅信任本测试动态生成的证书，不用于生产通信。
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authenticationType) {
                // 自动化客户端仅信任本测试动态生成的证书，不用于生产通信。
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{trustManager}, new SecureRandom());
        return context;
    }
}
