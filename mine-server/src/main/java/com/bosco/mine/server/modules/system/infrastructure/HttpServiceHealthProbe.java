package com.bosco.mine.server.modules.system.infrastructure;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 通过服务内部 Actuator 健康地址按需检查网关或遥测进程。
 */
public final class HttpServiceHealthProbe implements ServiceHealthProbe {

    private final String key;
    private final URI healthUri;
    private final Duration timeout;
    private final HttpClient httpClient;

    /**
     * 创建远程服务健康探测。
     *
     * @param key       服务稳定标识
     * @param healthUri 服务内部健康地址
     * @param timeout   单次检查超时
     */
    public HttpServiceHealthProbe(String key, URI healthUri, Duration timeout) {
        this.key = key;
        this.healthUri = healthUri;
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
    }

    /**
     * 执行一次远程服务健康检查。
     *
     * @return 脱敏探测结果
     */
    @Override
    public ProbeResult probe() {
        long startedAt = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder(healthUri)
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString()
            );
            boolean up = response.statusCode() >= 200
                    && response.statusCode() < 300
                    && response.body().contains("\"status\":\"UP\"");
            return new ProbeResult(
                    key, category(), up ? "UP" : "DOWN", elapsedMillis(startedAt),
                    up ? "serviceUp" : "healthFailed"
            );
        } catch (IOException exception) {
            throw new IllegalStateException("服务健康地址不可达", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("服务健康检查被中断", exception);
        }
    }

    /**
     * 返回服务稳定标识。
     *
     * @return 服务稳定标识
     */
    @Override
    public String key() {
        return key;
    }

    /**
     * 返回服务分组。
     *
     * @return 服务分组
     */
    @Override
    public String category() {
        return "SERVICE";
    }

    private long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }
}
