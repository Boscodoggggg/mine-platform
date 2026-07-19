package com.bosco.mine.simulator.transport;

import com.bosco.mine.simulator.profile.PayloadFormat;
import com.bosco.mine.simulator.profile.SimulatorProfile;
import com.bosco.mine.simulator.profile.TransportMode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 使用本机回环网络验证 TCP 传输实现。
 */
class TcpTransportTest {

    @Test
    void 客户端模式应连接服务端并发送字节() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            List<String> logs = new CopyOnWriteArrayList<>();
            CompletableFuture<byte[]> received = CompletableFuture.supplyAsync(() -> {
                try (Socket socket = server.accept(); InputStream input = socket.getInputStream()) {
                    byte[] payload = input.readNBytes(4);
                    socket.getOutputStream().write(new byte[]{0x11, 0x22});
                    return payload;
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
            try (TcpClientTransport transport = new TcpClientTransport(
                    profile(TransportMode.TCP_CLIENT, server.getLocalPort()), logs::add)) {
                transport.connect();
                assertThat(transport.state()).isEqualTo(TransportState.CONNECTED);
                transport.send(new byte[]{0x01, 0x02, 0x03, 0x04});

                assertThat(received.get()).containsExactly(0x01, 0x02, 0x03, 0x04);
                await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                    assertThat(logs).anyMatch(message -> message.contains("1122"));
                    assertThat(transport.state()).isEqualTo(TransportState.DISCONNECTED);
                });
            }
        }
    }

    @Test
    void 服务端模式应等待平台连接后发送字节() throws Exception {
        int port;
        try (ServerSocket available = new ServerSocket(0)) {
            port = available.getLocalPort();
        }
        List<String> logs = new CopyOnWriteArrayList<>();
        try (TcpServerTransport transport = new TcpServerTransport(
                profile(TransportMode.TCP_SERVER, port), logs::add)) {
            transport.connect();
            assertThat(transport.state()).isEqualTo(TransportState.WAITING);

            try (Socket platform = new Socket("127.0.0.1", port)) {
                await().atMost(Duration.ofSeconds(2))
                        .until(() -> transport.state() == TransportState.CONNECTED);
                platform.getOutputStream().write(new byte[]{0x10, 0x20});
                transport.send(new byte[]{0x68, 0x16});

                assertThat(platform.getInputStream().readNBytes(2)).containsExactly(0x68, 0x16);
                await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                        assertThat(logs).anyMatch(message -> message.contains("1020")));
            }
            await().atMost(Duration.ofSeconds(2)).until(() -> transport.state() == TransportState.WAITING);
            try (Socket reconnected = new Socket("127.0.0.1", port)) {
                await().atMost(Duration.ofSeconds(2)).until(() -> transport.state() == TransportState.CONNECTED);
            }
        }
    }

    @Test
    void 服务端快速重连不应被旧接收线程污染状态() throws Exception {
        int port;
        try (ServerSocket available = new ServerSocket(0)) {
            port = available.getLocalPort();
        }
        try (TcpServerTransport transport = new TcpServerTransport(
                profile(TransportMode.TCP_SERVER, port), ignored -> { })) {
            for (int index = 0; index < 20; index++) {
                transport.connect();
            }
            try (Socket platform = new Socket("127.0.0.1", port)) {
                await().atMost(Duration.ofSeconds(2)).until(() -> transport.state() == TransportState.CONNECTED);
                transport.send(new byte[]{0x01});
                assertThat(platform.getInputStream().read()).isEqualTo(0x01);
            }
        }
    }

    @Test
    void 服务端监听端口短暂占用后应在有限时间内恢复() throws Exception {
        ServerSocket occupied = new ServerSocket();
        occupied.setReuseAddress(false);
        occupied.bind(new InetSocketAddress("127.0.0.1", 0));
        int port = occupied.getLocalPort();
        CountDownLatch releaseStarted = new CountDownLatch(1);
        AtomicReference<Throwable> releaseFailure = new AtomicReference<>();
        Thread release = Thread.ofPlatform().name("tcp-test-port-release").start(() -> {
            releaseStarted.countDown();
            try {
                Thread.sleep(300);
                occupied.close();
            } catch (Throwable throwable) {
                if (throwable instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                releaseFailure.set(throwable);
            }
        });
        assertThat(releaseStarted.await(1, TimeUnit.SECONDS)).isTrue();

        try (TcpServerTransport transport = new TcpServerTransport(
                profile(TransportMode.TCP_SERVER, port), ignored -> { })) {
            transport.connect();
            assertThat(transport.state()).isEqualTo(TransportState.WAITING);
        } finally {
            occupied.close();
            release.join(2_000);
        }
        assertThat(release.isAlive()).isFalse();
        assertThat(releaseFailure.get()).isNull();
    }

    private SimulatorProfile profile(TransportMode mode, int port) {
        return new SimulatorProfile(
                "tcp-1", "TCP 测试", mode, "127.0.0.1", port, false,
                "", "", "", "", "", PayloadFormat.HEX, "6816", 1000, 0);
    }
}
