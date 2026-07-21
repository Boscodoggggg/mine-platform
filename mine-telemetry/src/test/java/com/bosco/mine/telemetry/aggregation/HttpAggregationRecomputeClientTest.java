package com.bosco.mine.telemetry.aggregation;

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
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验显式重算内部客户端的服务令牌、严格解码、终态回写和无重定向边界。
 */
class HttpAggregationRecomputeClientTest {

    @TempDir
    Path tempDir;

    @Test
    void 应使用外部服务令牌认领并携带当前令牌完成请求() throws Exception {
        UUID requestId = uuid(861);
        UUID executionId = uuid(862);
        UUID claimToken = uuid(863);
        UUID deviceId = uuid(864);
        String token = "aggregation-service-token-with-at-least-32-bytes";
        Path tokenFile = tempDir.resolve("service-token");
        Files.writeString(tokenFile, token);
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/aggregation-recomputations", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("X-Mine-Service-Token"))
                    .isEqualTo(token);
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (path.endsWith("/claim")) {
                calls.incrementAndGet();
                assertThat(body).contains(
                        "\"instanceId\":\"telemetry-a\"",
                        "\"leaseDuration\":\"PT10M\"");
                byte[] response = ("""
                        {
                          "requestId":"%s",
                          "executionId":"%s",
                          "claimToken":"%s",
                          "deviceId":"%s",
                          "propertyId":"engine.rpm",
                          "bucket":"PT5M",
                          "from":"2026-07-17T10:00:00Z",
                          "to":"2026-07-17T10:05:00Z",
                          "leaseUntil":"2026-07-17T10:10:00Z",
                          "attemptCount":2
                        }
                        """).formatted(requestId, executionId, claimToken, deviceId)
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } else {
                calls.incrementAndGet();
                assertThat(path).isEqualTo(
                        "/internal/v1/aggregation-recomputations/" + requestId + "/complete");
                assertThat(body).contains(
                        "\"executionId\":\"" + executionId + "\"",
                        "\"claimToken\":\"" + claimToken + "\"",
                        "\"status\":\"SUCCEEDED\"")
                        .contains("\"error\":null");
                exchange.sendResponseHeaders(204, -1);
            }
            exchange.close();
        });
        server.start();
        try {
            HttpAggregationRecomputeClient client = client(server, tokenFile);

            AggregationRecomputeClient.Claim claim = client
                    .claim("telemetry-a", Duration.ofMinutes(10)).orElseThrow();
            AggregationRecomputeClient.CompletionResult completed = client.complete(
                    claim, AggregationRecomputeClient.CompletionStatus.SUCCEEDED, null);

            assertThat(claim.requestId()).isEqualTo(requestId);
            assertThat(claim.executionId()).isEqualTo(executionId);
            assertThat(claim.claimToken()).isEqualTo(claimToken);
            assertThat(claim.deviceId()).isEqualTo(deviceId);
            assertThat(claim.bucket()).isEqualTo(Duration.ofMinutes(5));
            assertThat(claim.attemptCount()).isEqualTo(2);
            assertThat(completed)
                    .isEqualTo(AggregationRecomputeClient.CompletionResult.COMPLETED);
            assertThat(calls).hasValue(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 应识别无任务和租约丢失且禁止跟随重定向() throws Exception {
        Path tokenFile = tempDir.resolve("service-token");
        Files.writeString(tokenFile, "aggregation-service-token-with-at-least-32-bytes");
        AtomicInteger mode = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/aggregation-recomputations", exchange -> {
            int current = mode.getAndIncrement();
            if (current == 0) exchange.sendResponseHeaders(204, -1);
            else if (current == 1) exchange.sendResponseHeaders(409, -1);
            else {
                exchange.getResponseHeaders().add("Location", "http://example.invalid/redirect");
                exchange.sendResponseHeaders(302, -1);
            }
            exchange.close();
        });
        server.start();
        try {
            HttpAggregationRecomputeClient client = client(server, tokenFile);
            AggregationRecomputeClient.Claim claim = claim();

            assertThat(client.claim("telemetry-a", Duration.ofMinutes(10))).isEmpty();
            assertThat(client.complete(
                    claim, AggregationRecomputeClient.CompletionStatus.FAILED, "query failed"))
                    .isEqualTo(AggregationRecomputeClient.CompletionResult.LEASE_LOST);
            assertThatThrownBy(() -> client.claim("telemetry-a", Duration.ofMinutes(10)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("HTTP 302");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 畸形响应和过短令牌不得降级执行() throws Exception {
        Path shortToken = tempDir.resolve("short-token");
        Files.writeString(shortToken, "too-short");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/aggregation-recomputations/claim", exchange -> {
            byte[] response = "{\"requestId\":null}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            assertThatThrownBy(() -> client(server, shortToken)
                    .claim("telemetry-a", Duration.ofMinutes(10)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("令牌长度不足");
            Path validToken = tempDir.resolve("valid-token");
            Files.writeString(validToken, "aggregation-service-token-with-at-least-32-bytes");
            assertThatThrownBy(() -> client(server, validToken)
                    .claim("telemetry-a", Duration.ofMinutes(10)))
                    .isInstanceOf(PermanentAggregationRecomputeException.class)
                    .hasMessageContaining("响应");
        } finally {
            server.stop(0);
        }
    }

    private HttpAggregationRecomputeClient client(HttpServer server, Path tokenFile) {
        return new HttpAggregationRecomputeClient(new StrategyDistributionProperties(
                true,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                tokenFile,
                100,
                Duration.ofSeconds(2)
        ));
    }

    private AggregationRecomputeClient.Claim claim() {
        return new AggregationRecomputeClient.Claim(
                uuid(871), uuid(872), uuid(873), uuid(874), "engine.rpm",
                Duration.ofMinutes(5), Instant.parse("2026-07-17T10:00:00Z"),
                Instant.parse("2026-07-17T10:05:00Z"),
                Instant.parse("2026-07-17T10:10:00Z"), 1);
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("019f6f00-0000-7000-8000-%012d".formatted(suffix));
    }
}
