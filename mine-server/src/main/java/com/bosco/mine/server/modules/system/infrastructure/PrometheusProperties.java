package com.bosco.mine.server.modules.system.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * 系统运行监控访问内部 Prometheus 的只读配置。
 *
 * @param enabled          是否启用历史指标查询
 * @param baseUrl          Prometheus 内部基础地址
 * @param connectTimeout   连接超时
 * @param requestTimeout   单次请求超时
 * @param maxResponseBytes 单次响应最大字节数
 */
@ConfigurationProperties("mine.operations.prometheus")
public record PrometheusProperties(
        boolean enabled,
        URI baseUrl,
        Duration connectTimeout,
        Duration requestTimeout,
        int maxResponseBytes
) {
    public PrometheusProperties {
        if (baseUrl == null || baseUrl.getScheme() == null
                || !("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))) {
            throw new IllegalArgumentException("Prometheus 基础地址必须使用 HTTP 或 HTTPS");
        }
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("Prometheus 连接超时必须大于零");
        }
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("Prometheus 请求超时必须大于零");
        }
        if (maxResponseBytes < 1024) {
            throw new IllegalArgumentException("Prometheus 响应上限不得小于 1024 字节");
        }
    }
}
