package com.bosco.mine.server.modules.system.infrastructure;

import com.bosco.mine.server.modules.system.service.OperationsObservabilityQuery;
import com.bosco.mine.server.modules.system.service.OperationsObservabilityService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验 Prometheus 适配器只执行固定历史查询并安全处理上游异常。
 */
class PrometheusOperationsQueryTest {

    private HttpServer server;

    @AfterEach
    void 停止模拟服务() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void 应使用固定Redis查询并解析矩阵采样点() throws Exception {
        AtomicReference<String> requestedPath = new AtomicReference<>();
        server = startServer(200, """
                {"status":"success","data":{"resultType":"matrix","result":[
                  {"metric":{"job":"redis"},"values":[[1784181540,"1"],[1784181600,"0"]]}
                ]}}
                """, requestedPath);
        PrometheusOperationsQuery query = new PrometheusOperationsQuery(
                properties(true), new ObjectMapper(), fixedClock()
        );

        OperationsObservabilityQuery.QueryResult result = query.query(
                OperationsObservabilityService.TimeRange.HOUR, Set.of("redis")
        );

        assertThat(result.available()).isTrue();
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.key()).isEqualTo("redis");
            assertThat(item.category()).isEqualTo("STORAGE");
            assertThat(item.statusPoints()).extracting(OperationsObservabilityQuery.StatusPoint::value)
                    .containsExactly(1, 0);
        });
        assertThat(requestedPath.get()).startsWith("/api/v1/query_range?");
        assertThat(requestedPath.get()).contains("query=redis_up%7Bjob%3D%22redis%22%7D");
        assertThat(requestedPath.get()).contains("step=60");
    }

    @Test
    void Prometheus未启用或返回错误时应返回不可用结果() throws Exception {
        PrometheusOperationsQuery disabled = new PrometheusOperationsQuery(
                properties(false), new ObjectMapper(), fixedClock()
        );

        assertThat(disabled.query(OperationsObservabilityService.TimeRange.DAY, Set.of("redis")).available())
                .isFalse();

        server = startServer(500, "internal secret response", new AtomicReference<>());
        PrometheusOperationsQuery failed = new PrometheusOperationsQuery(
                properties(true), new ObjectMapper(), fixedClock()
        );

        OperationsObservabilityQuery.QueryResult result = failed.query(
                OperationsObservabilityService.TimeRange.DAY, Set.of("redis")
        );

        assertThat(result.available()).isFalse();
        assertThat(result.items()).isEmpty();
    }

    private HttpServer startServer(int status, String body, AtomicReference<String> requestedPath) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/api/v1/query_range", exchange -> {
            requestedPath.set(exchange.getRequestURI().toString());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        httpServer.start();
        return httpServer;
    }

    private PrometheusProperties properties(boolean enabled) {
        return new PrometheusProperties(
                enabled,
                URI.create("http://127.0.0.1:" + (server == null ? 9 : server.getAddress().getPort())),
                Duration.ofMillis(200),
                Duration.ofSeconds(1),
                1_000_000
        );
    }

    private Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-07-16T06:00:00Z"), ZoneOffset.UTC);
    }
}
