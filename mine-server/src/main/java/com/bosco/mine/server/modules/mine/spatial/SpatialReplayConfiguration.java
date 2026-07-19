package com.bosco.mine.server.modules.mine.spatial;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配空间重放登记最小权限连接。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SpatialReplayProperties.class)
@ConditionalOnProperty(prefix = "mine.spatial-replay", name = "enabled", havingValue = "true")
public class SpatialReplayConfiguration {

    /**
     * 创建空间重放登记连接。
     *
     * @param properties 空间重放连接参数
     * @return 受管重放连接
     */
    @Bean(destroyMethod = "close")
    public SpatialReplayConnection spatialReplayConnection(SpatialReplayProperties properties) {
        return new SpatialReplayConnection(properties);
    }
}
