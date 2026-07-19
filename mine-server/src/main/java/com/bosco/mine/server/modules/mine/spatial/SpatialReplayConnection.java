package com.bosco.mine.server.modules.mine.spatial;

import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 管理空间重放登记专用连接池，不注册为主业务数据源。
 */
public final class SpatialReplayConnection implements AutoCloseable {

    private final HikariDataSource dataSource;

    /**
     * 从最小权限配置创建重放登记连接池。
     *
     * @param properties 空间重放连接参数
     */
    public SpatialReplayConnection(SpatialReplayProperties properties) {
        validate(properties);
        this.dataSource = new HikariDataSource();
        dataSource.setPoolName("mine-spatial-replay");
        dataSource.setJdbcUrl(properties.url());
        dataSource.setUsername(properties.username());
        dataSource.setPassword(readSecret(properties));
        dataSource.setMaximumPoolSize(properties.maximumPoolSize());
        dataSource.setMinimumIdle(0);
        dataSource.setReadOnly(false);
        dataSource.setAutoCommit(true);
        dataSource.setConnectionTimeout(properties.connectionTimeout().toMillis());
        dataSource.setConnectionInitSql(
                "SET statement_timeout = '" + properties.statementTimeout().toMillis() + "ms'");
    }

    /**
     * 返回只供重放登记服务使用的数据源。
     *
     * @return 重放登记数据源
     */
    public DataSource dataSource() {
        return dataSource;
    }

    /** 关闭重放登记连接池。 */
    @Override
    public void close() {
        dataSource.close();
    }

    private void validate(SpatialReplayProperties properties) {
        if (properties == null
                || properties.url() == null || properties.url().isBlank()
                || properties.username() == null || properties.username().isBlank()
                || properties.passwordFile() == null
                || properties.maximumPoolSize() < 1
                || invalid(properties.connectionTimeout())
                || invalid(properties.statementTimeout())) {
            throw new IllegalStateException("空间重放数据库配置不完整");
        }
    }

    private boolean invalid(java.time.Duration duration) {
        return duration == null || duration.isZero() || duration.isNegative();
    }

    private String readSecret(SpatialReplayProperties properties) {
        try {
            String password = Files.readString(
                    properties.passwordFile(), StandardCharsets.UTF_8).strip();
            if (password.isEmpty()) {
                throw new IllegalStateException("空间重放数据库密码不能为空");
            }
            return password;
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取空间重放数据库密码文件", exception);
        }
    }
}
