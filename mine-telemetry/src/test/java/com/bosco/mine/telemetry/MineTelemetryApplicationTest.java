package com.bosco.mine.telemetry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验遥测处理服务可以独立启动并对现场运维暴露基础端点。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
)
class MineTelemetryApplicationTest {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Value("${local.server.port}")
    private int serverPort;

    @Value("${local.management.port}")
    private int managementPort;

    @Test
    void 应在独立管理端口暴露运维端点且业务端口不可访问() throws Exception {
        assertThat(managementPort).isNotEqualTo(serverPort);
        assertEndpointContains(managementPort, "/actuator/health", "\"status\":\"UP\"");
        assertEndpointContains(managementPort, "/actuator/info", "\"name\":\"mine-telemetry\"");
        assertEndpointContains(managementPort, "/actuator/metrics", "jvm.memory.used");
        assertEndpointContains(managementPort, "/actuator/prometheus", "jvm_memory_used_bytes");
        assertEndpointStatus(serverPort, "/actuator/health", 404);
    }

    private void assertEndpointContains(int port, String path, String expectedContent) throws Exception {
        HttpResponse<String> response = get(port, path);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(expectedContent);
    }

    private void assertEndpointStatus(int port, String path, int expectedStatus) throws Exception {
        assertThat(get(port, path).statusCode()).isEqualTo(expectedStatus);
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:%d%s".formatted(port, path))
                )
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
    }
}
