package com.bosco.mine.server.modules.device.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 创建设备遥测查询专用的只读时序库连接池。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TelemetryQueryProperties.class)
@ConditionalOnProperty(prefix = "mine.telemetry-query", name = "enabled", havingValue = "true")
public class TelemetryQueryConfiguration {

    /**
     * 创建遥测查询受管连接，密码只允许从容器密钥文件读取。
     *
     * @param properties 遥测查询连接参数
     * @return 遥测查询受管连接
     */
    @Bean(destroyMethod = "close")
    public TelemetryQueryConnection telemetryQueryConnection(
            TelemetryQueryProperties properties
    ) {
        return new TelemetryQueryConnection(properties);
    }
}
