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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MineTelemetryApplicationTest {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Value("${local.server.port}")
    private int serverPort;

    @Test
    void 应启动并暴露健康信息和指标端点() throws Exception {
        assertEndpointContains("/actuator/health", "\"status\":\"UP\"");
        assertEndpointContains("/actuator/info", "\"name\":\"mine-telemetry\"");
        assertEndpointContains("/actuator/metrics", "jvm.memory.used");
    }

    private void assertEndpointContains(String path, String expectedContent) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:%d%s".formatted(serverPort, path))
                )
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(expectedContent);
    }
}
