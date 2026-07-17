package com.bosco.mine.telemetry.strategy;

import com.bosco.mine.telemetry.bootstrap.StrategyDistributionProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验策略快照 HTTP 客户端携带外部服务令牌并解析结构化分页响应。
 */
class HttpStrategySnapshotSourceTest {

    @TempDir
    Path tempDir;

    @Test
    void 应携带服务令牌并解析策略快照页() throws Exception {
        UUID snapshotId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        String token = "strategy-service-token-with-at-least-32-bytes";
        Path tokenFile = tempDir.resolve("service-token");
        Files.writeString(tokenFile, token);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/strategy-runtime-snapshots/" + snapshotId, exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-Mine-Service-Token"))
                    .isEqualTo(token);
            assertThat(exchange.getRequestURI().getQuery()).isEqualTo("limit=100");
            byte[] body = ("""
                    {
                      "snapshotId":"%s",
                      "productId":"%s",
                      "strategyType":"DATA_QUALITY",
                      "runtimeRevision":7,
                      "itemCount":1,
                      "contentHash":"sha256:%s",
                      "items":[{
                        "deviceId":"%s",
                        "values":{"staleAfterSeconds":60},
                        "contentHash":"sha256:%s"
                      }],
                      "nextAfterDeviceId":null
                    }
                    """).formatted(
                    snapshotId, productId, "a".repeat(64), deviceId, "b".repeat(64))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            HttpStrategySnapshotSource source = new HttpStrategySnapshotSource(
                    new StrategyDistributionProperties(
                            true,
                            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                            tokenFile,
                            100,
                            Duration.ofSeconds(2)
                    ));

            StrategySnapshotSource.SnapshotPage page = source.fetch(snapshotId, null, 100);

            assertThat(page.runtimeRevision()).isEqualTo(7);
            assertThat(page.items()).singleElement().satisfies(item -> {
                assertThat(item.deviceId()).isEqualTo(deviceId);
                assertThat(item.values()).containsEntry("staleAfterSeconds", 60);
            });
        } finally {
            server.stop(0);
        }
    }
}
