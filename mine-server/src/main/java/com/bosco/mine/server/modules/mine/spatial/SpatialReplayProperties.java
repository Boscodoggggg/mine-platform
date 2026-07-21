package com.bosco.mine.server.modules.mine.spatial;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 空间重放登记使用的 TimescaleDB 最小权限连接参数。
 *
 * @param enabled 是否启用重放登记接口
 * @param url 时序数据库 JDBC 地址
 * @param username 仅可读写重放请求表的数据库用户名
 * @param passwordFile 数据库密码密钥文件
 * @param maximumPoolSize 最大连接数
 * @param connectionTimeout 获取连接超时
 * @param statementTimeout 单条语句超时
 */
@ConfigurationProperties("mine.spatial-replay")
public record SpatialReplayProperties(
        boolean enabled,
        String url,
        String username,
        Path passwordFile,
        int maximumPoolSize,
        Duration connectionTimeout,
        Duration statementTimeout
) {
}
