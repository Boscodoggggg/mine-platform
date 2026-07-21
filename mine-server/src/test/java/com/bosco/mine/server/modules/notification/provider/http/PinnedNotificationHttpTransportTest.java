package com.bosco.mine.server.modules.notification.provider.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PinnedNotificationHttpTransportTest {

    @Test
    void 必须使用安全策略固定的地址而不是二次查询DNS() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/hook", exchange -> {
                exchange.sendResponseHeaders(202, 2);
                exchange.getResponseBody().write("ok".getBytes(StandardCharsets.UTF_8));
                exchange.close();
            });
            server.start();
            try {
                var endpoint = new NotificationEndpointPolicy.ValidatedEndpoint(
                        URI.create("http://never-resolve.invalid:" + server.getAddress().getPort()
                                + "/hook"),
                        List.of(InetAddress.getByName("127.0.0.1")));

                NotificationHttpTransport.Response response = new PinnedNotificationHttpTransport()
                        .post(new NotificationHttpTransport.Request(
                                endpoint, Map.of(), "{}", Duration.ofSeconds(1),
                                Duration.ofSeconds(1)));

                assertThat(response.statusCode()).isEqualTo(202);
                assertThat(response.body()).isEqualTo("ok");
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void 响应头到达后正文停滞仍必须受读取超时约束() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/slow", exchange -> {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('x');
                exchange.getResponseBody().flush();
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.start();
            try {
                var endpoint = new NotificationEndpointPolicy.ValidatedEndpoint(
                        URI.create("http://slow.invalid:" + server.getAddress().getPort() + "/slow"),
                        List.of(InetAddress.getByName("127.0.0.1")));
                var request = new NotificationHttpTransport.Request(
                        endpoint, Map.of(), "{}", Duration.ofSeconds(1),
                        Duration.ofMillis(100));

                assertThatThrownBy(() -> new PinnedNotificationHttpTransport().post(request))
                        .isInstanceOf(NotificationHttpTransport.TransportException.class);
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void 读取响应时必须在内存分配前执行字节上限() {
        byte[] accepted = "ok".getBytes(StandardCharsets.UTF_8);
        byte[] oversized = new byte[64 * 1024 + 1];

        assertThat(PinnedNotificationHttpTransport.readBounded(
                new ByteArrayInputStream(accepted))).isEqualTo(accepted);
        assertThatThrownBy(() -> PinnedNotificationHttpTransport.readBounded(
                new ByteArrayInputStream(oversized)))
                .isInstanceOf(NotificationHttpTransport.TransportException.class)
                .satisfies(exception -> assertThat(((NotificationHttpTransport.TransportException) exception)
                        .requestMayHaveBeenSent()).isTrue());
    }
}
