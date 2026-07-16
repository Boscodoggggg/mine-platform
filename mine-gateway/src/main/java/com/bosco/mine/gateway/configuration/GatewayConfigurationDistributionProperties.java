package com.bosco.mine.gateway.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Gateway 配置事件消费和内部快照拉取参数。
 *
 * @param enabled 是否启用动态配置分发并强制产品先加载快照
 * @param serverBaseUrl 管理服务内部地址
 * @param serviceTokenFile 服务令牌 secret 文件
 * @param requestTimeout 快照拉取超时
 * @param maxSnapshotBytes 单个快照响应最大字节数
 * @param nodeId 部署环境内稳定 Gateway 节点标识
 */
@ConfigurationProperties("mine.gateway.configuration-distribution")
public record GatewayConfigurationDistributionProperties(
        Boolean enabled,
        URI serverBaseUrl,
        Path serviceTokenFile,
        Duration requestTimeout,
        Integer maxSnapshotBytes,
        String nodeId
) {

    /** @return 是否启用动态配置分发 */
    public boolean resolvedEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    /** @return 管理服务内部地址 */
    public URI resolvedServerBaseUrl() {
        URI value = serverBaseUrl == null ? URI.create("http://mine-server:8080") : serverBaseUrl;
        String scheme = value.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || value.getHost() == null) {
            throw new IllegalArgumentException("配置管理服务地址必须是有效的 HTTP(S) URI");
        }
        return value;
    }

    /** @return 服务令牌 secret 文件 */
    public Path resolvedServiceTokenFile() {
        return serviceTokenFile == null
                ? Path.of("/run/secrets/mine_configuration_distribution_token")
                : serviceTokenFile;
    }

    /** @return 单次 HTTP 请求超时 */
    public Duration resolvedRequestTimeout() {
        Duration value = requestTimeout == null ? Duration.ofSeconds(5) : requestTimeout;
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("配置快照请求超时必须大于 0");
        }
        return value;
    }

    /** @return 单个快照响应最大字节数 */
    public int resolvedMaxSnapshotBytes() {
        int value = maxSnapshotBytes == null ? 2 * 1024 * 1024 : maxSnapshotBytes;
        if (value < 1024 || value > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("配置快照大小上限必须在 1 KiB 到 16 MiB 之间");
        }
        return value;
    }

    /** @return 稳定节点标识 */
    public String resolvedNodeId() {
        String value = nodeId == null ? "standalone" : nodeId.strip();
        if (!value.matches("^[A-Za-z0-9._-]{1,128}$")) {
            throw new IllegalArgumentException("Gateway 节点标识只能包含字母、数字、点、下划线和连字符");
        }
        return value;
    }
}
