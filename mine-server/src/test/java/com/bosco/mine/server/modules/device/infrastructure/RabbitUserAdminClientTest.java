package com.bosco.mine.server.modules.device.infrastructure;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验 RabbitMQ 管理面活动连接查询的路径、鉴权和脱敏解析。
 */
class RabbitUserAdminClientTest {

    @TempDir
    Path tempDirectory;

    @Test
    void 应按用户名编码查询并仅返回连接时间() throws Exception {
        Path usernameFile = writeSecret("username", "admin");
        Path passwordFile = writeSecret("password", "secret");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/connections/username/", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            assertThat(exchange.getRequestURI().getRawPath())
                    .isEqualTo("/api/connections/username/device%20user");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).startsWith("Basic ");
            respond(exchange, "[{\"connected_at\":1784188795000,\"peer_host\":\"10.0.0.8\"}]");
        });
        server.start();
        try {
            RabbitUserAdminClient client = new RabbitUserAdminClient(
                    new RabbitAdminProperties(
                            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                            usernameFile, passwordFile, "/mine-device",
                            Duration.ofSeconds(1), Duration.ofSeconds(1)
                    ),
                    new ObjectMapper()
            );

            List<RabbitUserAdminClient.ConnectionSummary> result = client.findConnections("device user");

            assertThat(result).containsExactly(new RabbitUserAdminClient.ConnectionSummary(
                    Instant.ofEpochMilli(1784188795000L)
            ));
        } finally {
            server.stop(0);
        }
    }

    private Path writeSecret(String name, String value) throws IOException {
        Path path = tempDirectory.resolve(name);
        Files.writeString(path, value, StandardCharsets.UTF_8);
        return path;
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
