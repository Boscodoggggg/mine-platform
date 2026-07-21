package com.bosco.mine.server.modules.device.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 注册 RabbitMQ 设备账号管理配置。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RabbitAdminProperties.class)
public class RabbitAdminConfiguration {
}
