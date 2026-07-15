package com.bosco.mine.server.modules.device.infrastructure;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 管理遥测查询专用连接池，但不注册为 Spring {@link javax.sql.DataSource}，避免覆盖业务主数据源。
 */
public final class TelemetryQueryConnection implements AutoCloseable {

    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate readOnlyTransaction;

    /**
     * 从配置和密钥文件创建只读 TimescaleDB 连接池。
     *
     * @param properties 遥测查询连接参数
     */
    public TelemetryQueryConnection(TelemetryQueryProperties properties) {
        validate(properties);
        this.dataSource = new HikariDataSource();
        dataSource.setPoolName("mine-telemetry-query");
        dataSource.setJdbcUrl(properties.url());
        dataSource.setUsername(properties.username());
        dataSource.setPassword(readSecret(properties));
        dataSource.setMaximumPoolSize(properties.maximumPoolSize());
        dataSource.setMinimumIdle(0);
        dataSource.setReadOnly(true);
        dataSource.setAutoCommit(true);
        dataSource.setConnectionTimeout(properties.connectionTimeout().toMillis());
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.setQueryTimeout(Math.toIntExact(properties.queryTimeout().toSeconds()));
        this.readOnlyTransaction = readOnlyTransaction(dataSource);
    }

    /**
     * 返回只访问遥测时序库的 JDBC 模板。
     *
     * @return 遥测查询 JDBC 模板
     */
    public JdbcTemplate jdbcTemplate() {
        return jdbcTemplate;
    }

    /**
     * 返回使用可重复读快照的只读事务模板，保证历史总数和当前页来自同一快照。
     *
     * @return 遥测只读事务模板
     */
    public TransactionTemplate readOnlyTransaction() {
        return readOnlyTransaction;
    }

    /**
     * 关闭遥测查询连接池。
     */
    @Override
    public void close() {
        dataSource.close();
    }

    private void validate(TelemetryQueryProperties properties) {
        if (properties.url() == null || properties.url().isBlank()
                || properties.username() == null || properties.username().isBlank()
                || properties.passwordFile() == null
                || properties.maximumPoolSize() < 1
                || invalidDuration(properties.connectionTimeout())
                || invalidDuration(properties.queryTimeout())
                || properties.queryTimeout().toSeconds() > Integer.MAX_VALUE) {
            throw new IllegalStateException("遥测查询数据库配置不完整");
        }
    }

    private TransactionTemplate readOnlyTransaction(HikariDataSource source) {
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(source)
        );
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return transaction;
    }

    private boolean invalidDuration(java.time.Duration duration) {
        return duration == null || duration.isNegative() || duration.isZero()
                || duration.toSeconds() == 0L;
    }

    private String readSecret(TelemetryQueryProperties properties) {
        try {
            String password = Files.readString(
                    properties.passwordFile(), StandardCharsets.UTF_8
            ).strip();
            if (password.isEmpty()) {
                throw new IllegalStateException("遥测查询数据库密码不能为空");
            }
            return password;
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取遥测查询数据库密码文件", exception);
        }
    }
}
