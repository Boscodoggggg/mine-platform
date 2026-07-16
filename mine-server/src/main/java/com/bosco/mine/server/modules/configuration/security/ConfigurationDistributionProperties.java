package com.bosco.mine.server.modules.configuration.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * 运行节点拉取不可变配置快照的内部接口安全参数。
 *
 * @param enabled 是否启用内部配置分发接口
 * @param serviceTokenFile 服务令牌 secret 文件
 */
@ConfigurationProperties("mine.configuration-distribution")
public record ConfigurationDistributionProperties(
        Boolean enabled,
        Path serviceTokenFile
) {

    /** @return 是否显式启用内部配置分发 */
    public boolean resolvedEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    /** @return 服务令牌 secret 文件 */
    public Path resolvedServiceTokenFile() {
        return serviceTokenFile == null
                ? Path.of("/run/secrets/mine_configuration_distribution_token")
                : serviceTokenFile;
    }
}
