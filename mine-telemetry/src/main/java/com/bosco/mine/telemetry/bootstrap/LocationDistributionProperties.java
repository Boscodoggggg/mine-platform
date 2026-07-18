package com.bosco.mine.telemetry.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Telemetry 拉取定位运行快照的安全和分页参数。
 *
 * @param enabled 是否启用定位快照分发
 * @param serverUrl mine-server 内部服务地址
 * @param serviceTokenFile 共享服务令牌文件
 * @param pageSize 单次拉取条目数
 * @param requestTimeout 单次请求超时
 */
@ConfigurationProperties("mine.telemetry.location-distribution")
public record LocationDistributionProperties(
        Boolean enabled,
        URI serverUrl,
        Path serviceTokenFile,
        Integer pageSize,
        Duration requestTimeout
) {

    /** @return 未显式启用时关闭定位快照分发 */
    public boolean resolvedEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    /** @return 控制面服务地址 */
    public URI resolvedServerUrl() {
        return serverUrl == null ? URI.create("http://mine-server:8080") : serverUrl;
    }

    /** @return 外部服务令牌文件 */
    public Path resolvedServiceTokenFile() {
        return serviceTokenFile == null
                ? Path.of("/run/secrets/mine_configuration_distribution_token")
                : serviceTokenFile;
    }

    /** @return 单页条目数，默认 500 */
    public int resolvedPageSize() {
        return pageSize == null ? 500 : pageSize;
    }

    /** @return 单次请求超时，默认 10 秒 */
    public Duration resolvedRequestTimeout() {
        return requestTimeout == null ? Duration.ofSeconds(10) : requestTimeout;
    }
}
