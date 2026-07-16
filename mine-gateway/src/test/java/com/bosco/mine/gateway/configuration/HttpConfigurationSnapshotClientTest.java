package com.bosco.mine.gateway.configuration;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验 Gateway 使用 secret 服务令牌拉取有大小上限的结构化配置快照。
 */
class HttpConfigurationSnapshotClientTest {

    @TempDir
    Path tempDir;

    private HttpServer server;

    @AfterEach
    void 停止测试服务() {
        if (server != null) server.stop(0);
    }

    @Test
    void 应携带外部服务令牌并解析完整快照() throws Exception {
        String token = "gateway-configuration-token-at-least-32-bytes";
        Path tokenFile = Files.writeString(tempDir.resolve("token"), token);
        UUID releaseId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID packageId = UUID.randomUUID();
        AtomicReference<String> receivedToken = new AtomicReference<>();
        String response = """
                {
                  "releaseId":"%s",
                  "productId":"%s",
                  "packageId":"%s",
                  "releaseSequence":7,
                  "contentHash":"sha256:%s",
                  "snapshot":{"schemaVersion":1,"productKey":"haul-truck"}
                }
                """.formatted(releaseId, productId, packageId, "a".repeat(64));
        start(exchange -> {
            receivedToken.set(exchange.getRequestHeaders().getFirst("X-Mine-Service-Token"));
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        HttpConfigurationSnapshotClient client = new HttpConfigurationSnapshotClient(properties(tokenFile, 4096));

        ConfigurationSnapshot snapshot = client.fetch(releaseId);

        assertThat(receivedToken.get()).isEqualTo(token);
        assertThat(snapshot.productId()).isEqualTo(productId);
        assertThat(snapshot.releaseSequence()).isEqualTo(7);
        assertThat(snapshot.snapshotJson()).contains("\"productKey\":\"haul-truck\"");
    }

    @Test
    void 超过上限的响应必须拒绝且不能进入协议编译() throws Exception {
        Path tokenFile = Files.writeString(
                tempDir.resolve("token"), "gateway-configuration-token-at-least-32-bytes");
        start(exchange -> {
            byte[] body = ("{" + " ".repeat(2048) + "}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        HttpConfigurationSnapshotClient client = new HttpConfigurationSnapshotClient(properties(tokenFile, 1024));

        assertThatThrownBy(() -> client.fetch(UUID.randomUUID()))
                .isInstanceOf(InvalidConfigurationSnapshotException.class)
                .hasMessageContaining("上限");
    }

    @Test
    void 加载成功后应携带节点标识和实际哈希回报状态() throws Exception {
        String token = "gateway-configuration-token-at-least-32-bytes";
        Path tokenFile = Files.writeString(tempDir.resolve("token"), token);
        UUID releaseId = UUID.randomUUID();
        AtomicReference<String> requestMethod = new AtomicReference<>();
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> receivedToken = new AtomicReference<>();
        start(exchange -> {
            requestMethod.set(exchange.getRequestMethod());
            requestPath.set(exchange.getRequestURI().getPath());
            receivedToken.set(exchange.getRequestHeaders().getFirst("X-Mine-Service-Token"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        HttpConfigurationSnapshotClient client = new HttpConfigurationSnapshotClient(properties(tokenFile, 4096));

        client.reportLoaded(releaseId, "sha256:" + "b".repeat(64));

        assertThat(requestMethod.get()).isEqualTo("POST");
        assertThat(requestPath.get()).isEqualTo(
                "/internal/v1/config-releases/" + releaseId + "/nodes/gateway-test");
        assertThat(receivedToken.get()).isEqualTo(token);
        assertThat(requestBody.get()).contains(
                "\"nodeType\":\"GATEWAY\"",
                "\"status\":\"LOADED\"",
                "\"actualHash\":\"sha256:" + "b".repeat(64) + "\"");
    }

    private GatewayConfigurationDistributionProperties properties(Path tokenFile, int maxBytes) {
        return new GatewayConfigurationDistributionProperties(
                true,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                tokenFile,
                Duration.ofSeconds(2),
                maxBytes,
                tokenFile.resolveSibling("protocol-snapshots.json"),
                1024 * 1024,
                "gateway-test"
        );
    }

    private void start(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/config-releases", handler);
        server.start();
    }
}
