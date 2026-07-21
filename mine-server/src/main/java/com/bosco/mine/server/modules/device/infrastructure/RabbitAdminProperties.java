package com.bosco.mine.server.modules.device.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/**
 * RabbitMQ 管理接口连接参数，管理员凭据只能从只读密钥文件加载。
 *
 * @param baseUrl          管理接口根地址
 * @param usernameFile     管理员用户名文件
 * @param passwordFile     管理员密码文件
 * @param vhost            设备 MQTT 虚拟主机
 * @param connectTimeout   建连超时
 * @param requestTimeout   单次请求超时
 */
@ConfigurationProperties("mine.rabbitmq.admin")
public record RabbitAdminProperties(
        URI baseUrl,
        Path usernameFile,
        Path passwordFile,
        String vhost,
        Duration connectTimeout,
        Duration requestTimeout
) {
}
