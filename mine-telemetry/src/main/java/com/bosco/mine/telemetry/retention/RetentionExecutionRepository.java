package com.bosco.mine.telemetry.retention;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 使用同一 TimescaleDB 会话持有单实例锁，并执行有界保留删除和执行事实写入。
 */
public final class RetentionExecutionRepository implements RetentionExecutor.Store {

    private static final String LOCK_SQL =
            "SELECT pg_try_advisory_lock(hashtextextended('mine:telemetry:retention', 0))";
    private static final String UNLOCK_SQL =
            "SELECT pg_advisory_unlock(hashtextextended('mine:telemetry:retention', 0))";

    private final DataSource dataSource;

    /**
     * 创建数据保留执行仓储。
     *
     * @param dataSource Telemetry 时序数据库数据源
     */
    public RetentionExecutionRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "数据保留数据源不能为空");
    }

    /** {@inheritDoc} */
    @Override
    public <T> Optional<T> withLock(RetentionExecutor.LockedWork<T> work) {
        Objects.requireNonNull(work, "数据保留锁内工作不能为空");
        try (Connection connection = dataSource.getConnection()) {
            if (!queryBoolean(connection, LOCK_SQL)) {
                return Optional.empty();
            }
            try {
                return Optional.of(Objects.requireNonNull(
                        work.execute(new JdbcSession(connection)),
                        "数据保留锁内工作不得返回空值"
                ));
            } finally {
                queryBoolean(connection, UNLOCK_SQL);
            }
        } catch (SQLException exception) {
            throw new RetentionStorageException("无法访问数据保留时序数据库", exception);
        }
    }

    private boolean queryBoolean(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                throw new SQLException("数据库锁语句没有返回结果");
            }
            return resultSet.getBoolean(1);
        }
    }

    /** 持有数据库会话级锁期间使用的 JDBC 操作集合。 */
    private static final class JdbcSession implements RetentionExecutor.Session {
        private final Connection connection;

        private JdbcSession(Connection connection) {
            this.connection = connection;
        }

        /** {@inheritDoc} */
        @Override
        public int recoverStaleExecutions(Duration timeout) {
            long seconds;
            try {
                seconds = timeout.toSeconds();
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("数据保留失联超时过大", exception);
            }
            if (seconds < 1) {
                throw new IllegalArgumentException("数据保留失联超时必须至少一秒");
            }
            return update("""
                    UPDATE telemetry_retention_execution
                       SET status = 'FAILED',
                           completed_at = CURRENT_TIMESTAMP,
                           last_error = '运行实例超过失联超时，已由后续执行收敛'
                     WHERE status = 'RUNNING'
                       AND started_at < CURRENT_TIMESTAMP - make_interval(secs => ?)
                    """, statement -> statement.setLong(1, seconds));
        }

        /** {@inheritDoc} */
        @Override
        public int countEnabledPolicies() {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT COUNT(*)
                      FROM telemetry_retention_policy
                     WHERE enabled
                    """); ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return Math.toIntExact(resultSet.getLong(1));
            } catch (SQLException exception) {
                throw storageFailure("无法统计启用的数据保留策略", exception);
            }
        }

        /** {@inheritDoc} */
        @Override
        public void startExecution(
                UUID executionId,
                String instanceId,
                int enabledPolicyCount
        ) {
            int updated = update("""
                    INSERT INTO telemetry_retention_execution (
                        execution_id, instance_id, status, policy_count
                    ) VALUES (?, ?, 'RUNNING', ?)
                    """, statement -> {
                statement.setObject(1, executionId);
                statement.setString(2, instanceId);
                statement.setInt(3, enabledPolicyCount);
            });
            requireSingleUpdate(updated, "创建数据保留执行事实");
        }

        /** {@inheritDoc} */
        @Override
        public int deleteRawTelemetryBatch(int limit) {
            return update("""
                    WITH expired AS MATERIALIZED (
                        SELECT numeric.time, numeric.event_id, numeric.property_id
                          FROM telemetry_numeric numeric
                          JOIN telemetry_retention_policy policy
                            ON policy.device_id = numeric.device_id
                         WHERE policy.enabled
                           AND numeric.time < CURRENT_TIMESTAMP
                               - make_interval(days => policy.raw_telemetry_days)
                           AND numeric.received_at < CURRENT_TIMESTAMP
                               - make_interval(days => policy.raw_telemetry_days)
                         ORDER BY numeric.time, numeric.event_id, numeric.property_id
                         LIMIT ?
                    )
                    DELETE FROM telemetry_numeric numeric
                    USING expired
                     WHERE numeric.time = expired.time
                       AND numeric.event_id = expired.event_id
                       AND numeric.property_id = expired.property_id
                    """, statement -> statement.setInt(1, limit));
        }

        /** {@inheritDoc} */
        @Override
        public int deleteQualityEvidenceBatch(int limit) {
            return update("""
                    WITH expired AS MATERIALIZED (
                        SELECT evidence.event_id, evidence.property_id, evidence.rule_code
                          FROM telemetry_quality_evidence evidence
                          JOIN telemetry_retention_policy policy
                            ON policy.device_id = evidence.device_id
                         WHERE policy.enabled
                           AND evidence.created_at < CURRENT_TIMESTAMP
                               - make_interval(days => policy.quality_evidence_days)
                         ORDER BY evidence.created_at, evidence.event_id,
                                  evidence.property_id, evidence.rule_code
                         LIMIT ?
                    )
                    DELETE FROM telemetry_quality_evidence evidence
                    USING expired
                     WHERE evidence.event_id = expired.event_id
                       AND evidence.property_id = expired.property_id
                       AND evidence.rule_code = expired.rule_code
                    """, statement -> statement.setInt(1, limit));
        }

        /** {@inheritDoc} */
        @Override
        public void checkpointExecution(
                UUID executionId,
                long rawRowsDeleted,
                long qualityRowsDeleted,
                int batchCount
        ) {
            int updated = update("""
                    UPDATE telemetry_retention_execution
                       SET raw_rows_deleted = ?,
                           quality_rows_deleted = ?,
                           batch_count = ?
                     WHERE execution_id = ?
                       AND status = 'RUNNING'
                    """, statement -> {
                statement.setLong(1, rawRowsDeleted);
                statement.setLong(2, qualityRowsDeleted);
                statement.setInt(3, batchCount);
                statement.setObject(4, executionId);
            });
            requireSingleUpdate(updated, "记录数据保留执行进度");
        }

        /** {@inheritDoc} */
        @Override
        public void completeExecution(
                UUID executionId,
                RetentionExecutor.RunStatus status,
                long rawRowsDeleted,
                long qualityRowsDeleted,
                int batchCount,
                String errorSummary
        ) {
            if (status == RetentionExecutor.RunStatus.SKIPPED_LOCKED) {
                throw new IllegalArgumentException("锁忙状态不能写入执行事实");
            }
            int updated = update("""
                    UPDATE telemetry_retention_execution
                       SET status = ?,
                           raw_rows_deleted = ?,
                           quality_rows_deleted = ?,
                           batch_count = ?,
                           completed_at = CURRENT_TIMESTAMP,
                           last_error = ?
                     WHERE execution_id = ?
                       AND status = 'RUNNING'
                    """, statement -> {
                statement.setString(1, status.name());
                statement.setLong(2, rawRowsDeleted);
                statement.setLong(3, qualityRowsDeleted);
                statement.setInt(4, batchCount);
                statement.setString(5, errorSummary);
                statement.setObject(6, executionId);
            });
            requireSingleUpdate(updated, "完成数据保留执行事实");
        }

        private int update(String sql, StatementBinder binder) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                binder.bind(statement);
                return statement.executeUpdate();
            } catch (SQLException exception) {
                throw storageFailure("数据保留数据库写入失败", exception);
            }
        }

        private void requireSingleUpdate(int updated, String action) {
            if (updated != 1) {
                throw new RetentionStorageException(action + "未影响唯一目标行");
            }
        }

        private RetentionStorageException storageFailure(String message, SQLException cause) {
            return new RetentionStorageException(message, cause);
        }
    }

    /** JDBC 参数绑定函数。 */
    @FunctionalInterface
    private interface StatementBinder {
        /** 绑定预编译语句参数。 */
        void bind(PreparedStatement statement) throws SQLException;
    }

    /** 数据保留数据库访问失败。 */
    public static final class RetentionStorageException extends RuntimeException {
        /** 创建无底层异常的存储失败。 */
        public RetentionStorageException(String message) {
            super(message);
        }

        /** 创建保留底层异常链的存储失败。 */
        public RetentionStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
