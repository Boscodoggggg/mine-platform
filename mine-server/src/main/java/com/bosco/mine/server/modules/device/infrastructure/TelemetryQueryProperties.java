package com.bosco.mine.server.modules.device.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 设备遥测查询使用的 TimescaleDB 独立连接参数。
 *
 * @param enabled 是否启用遥测查询接口
 * @param url 时序数据库 JDBC 地址
 * @param username 时序数据库用户名
 * @param passwordFile 时序数据库密码文件
 * @param maximumPoolSize 最大连接数
 * @param connectionTimeout 获取连接超时
 * @param queryTimeout 单条查询超时
 */
@ConfigurationProperties("mine.telemetry-query")
public record TelemetryQueryProperties(
        boolean enabled,
        String url,
        String username,
        Path passwordFile,
        int maximumPoolSize,
        Duration connectionTimeout,
        Duration queryTimeout
) {
}
