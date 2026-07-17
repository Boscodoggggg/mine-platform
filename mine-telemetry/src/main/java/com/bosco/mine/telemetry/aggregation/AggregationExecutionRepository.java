package com.bosco.mine.telemetry.aggregation;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 使用同一 TimescaleDB 会话持有聚合单实例锁，并原子追加聚合版本和执行进度。
 */
public final class AggregationExecutionRepository {

    private static final String LOCK_SQL =
            "SELECT pg_try_advisory_lock(hashtextextended('mine:telemetry:aggregation', 0))";
    private static final String UNLOCK_SQL =
            "SELECT pg_advisory_unlock(hashtextextended('mine:telemetry:aggregation', 0))";
    private static final Set<Integer> BUCKET_SECONDS = Set.of(60, 300, 900, 3600, 21600, 86400);
    private static final Pattern PROPERTY_ID =
            Pattern.compile("^[A-Za-z][A-Za-z0-9_.-]{0,127}$");
    private static final Pattern CONTENT_HASH = Pattern.compile("^sha256:[0-9a-f]{64}$");

    private final DataSource dataSource;

    /**
     * 创建数值聚合执行仓储。
     *
     * @param dataSource Telemetry 时序数据库数据源
     */
    public AggregationExecutionRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "数值聚合数据源不能为空");
    }

    /**
     * 尝试获得数据库会话级聚合锁并执行工作。
     *
     * @param work 只能在锁生命周期内使用数据库会话的工作
     * @param <T> 工作结果类型
     * @return 锁忙时为空，否则为工作结果
     */
    public <T> Optional<T> withLock(LockedWork<T> work) {
        Objects.requireNonNull(work, "数值聚合锁内工作不能为空");
        try (Connection connection = dataSource.getConnection()) {
            enableAutoCommit(connection);
            if (!queryBoolean(connection, LOCK_SQL)) {
                return Optional.empty();
            }
            try {
                return Optional.of(Objects.requireNonNull(
                        work.execute(new JdbcSession(connection)),
                        "数值聚合锁内工作不得返回空值"
                ));
            } finally {
                queryBoolean(connection, UNLOCK_SQL);
            }
        } catch (SQLException exception) {
            throw new AggregationStorageException("无法访问数值聚合时序数据库", exception);
        }
    }

    /**
     * 确保锁会话使用自动提交，避免会话级锁与未提交事务相互影响。
     */
    private void enableAutoCommit(Connection connection) throws SQLException {
        if (!connection.getAutoCommit()) {
            connection.setAutoCommit(true);
        }
    }

    /**
     * 执行返回单个布尔值的数据库锁语句。
     */
    private boolean queryBoolean(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                throw new SQLException("数据库锁语句没有返回结果");
            }
            return resultSet.getBoolean(1);
        }
    }

    /**
     * 持有数据库会话级聚合锁期间使用的 JDBC 操作集合。
     */
    private static final class JdbcSession implements Session {
        private final Connection connection;

        private JdbcSession(Connection connection) {
            this.connection = connection;
        }

        /** {@inheritDoc} */
        @Override
        public void startExecution(
                UUID executionId,
                String instanceId,
                int policyCount,
                int ruleCount,
                long sourceWatermark
        ) {
            Objects.requireNonNull(executionId, "数值聚合执行标识不能为空");
            String normalizedInstance = requireText(instanceId, "数值聚合实例标识", 128);
            if (policyCount < 0 || ruleCount < 0 || sourceWatermark < 0) {
                throw new IllegalArgumentException("数值聚合执行计数和源水位不得为负数");
            }
            int updated = update("""
                    INSERT INTO telemetry_aggregation_execution (
                        execution_id, instance_id, status, policy_count,
                        rule_count, source_watermark
                    ) VALUES (?, ?, 'RUNNING', ?, ?, ?)
                    """, statement -> {
                statement.setObject(1, executionId);
                statement.setString(2, normalizedInstance);
                statement.setInt(3, policyCount);
                statement.setInt(4, ruleCount);
                statement.setLong(5, sourceWatermark);
            });
            requireSingleUpdate(updated, "创建数值聚合执行事实");
        }

        /** {@inheritDoc} */
        @Override
        public int writeRollupBatch(UUID executionId, List<RollupWrite> rollups) {
            Objects.requireNonNull(executionId, "数值聚合执行标识不能为空");
            List<RollupWrite> immutableRollups = List.copyOf(
                    Objects.requireNonNull(rollups, "数值聚合批次不能为空"));
            if (immutableRollups.size() > 100_000) {
                throw new IllegalArgumentException("单次数值聚合写入不得超过 100000 个桶");
            }
            if (immutableRollups.isEmpty()) {
                return 0;
            }
            return inBatchTransaction(() -> {
                int inserted = 0;
                long maximumRevision = 0;
                for (RollupWrite rollup : immutableRollups) {
                    long revision = insertRollup(executionId, rollup);
                    if (revision > 0) {
                        inserted = Math.incrementExact(inserted);
                        maximumRevision = Math.max(maximumRevision, revision);
                    }
                }
                if (inserted > 0) {
                    checkpointExecution(executionId, inserted, maximumRevision);
                }
                return inserted;
            });
        }

        /**
         * 追加一个聚合版本，语义相同的同轮重试返回零修订号。
         */
        private long insertRollup(UUID executionId, RollupWrite rollup) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO telemetry_numeric_rollup (
                        device_id, product_key, property_id, bucket_seconds,
                        bucket_start, bucket_end, average_value, minimum_value,
                        maximum_value, sample_count, worst_quality, latest_received_at,
                        source_watermark, runtime_revision, snapshot_id,
                        policy_content_hash, execution_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (
                        bucket_start, execution_id, device_id, property_id, bucket_seconds
                    ) DO NOTHING
                    RETURNING rollup_revision
                    """)) {
                statement.setObject(1, rollup.deviceId());
                statement.setString(2, rollup.productKey());
                statement.setString(3, rollup.propertyId());
                statement.setInt(4, rollup.bucketSeconds());
                statement.setTimestamp(5, Timestamp.from(rollup.bucketStart()));
                statement.setTimestamp(6, Timestamp.from(rollup.bucketEnd()));
                statement.setDouble(7, rollup.averageValue());
                statement.setDouble(8, rollup.minimumValue());
                statement.setDouble(9, rollup.maximumValue());
                statement.setLong(10, rollup.sampleCount());
                statement.setShort(11, rollup.worstQuality());
                statement.setTimestamp(12, Timestamp.from(rollup.latestReceivedAt()));
                statement.setLong(13, rollup.sourceWatermark());
                statement.setLong(14, rollup.runtimeRevision());
                statement.setObject(15, rollup.snapshotId());
                statement.setString(16, rollup.policyContentHash());
                statement.setObject(17, executionId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? resultSet.getLong(1) : 0;
                }
            } catch (SQLException exception) {
                throw storageFailure("无法追加版本化数值聚合事实", exception);
            }
        }

        /**
         * 在聚合版本所在事务内单向增加执行进度。
         */
        private void checkpointExecution(UUID executionId, int inserted, long maximumRevision) {
            int updated = update("""
                    UPDATE telemetry_aggregation_execution
                       SET bucket_count = bucket_count + ?,
                           rollup_watermark = GREATEST(
                               COALESCE(rollup_watermark, 0), ?
                           )
                     WHERE execution_id = ?
                       AND status = 'RUNNING'
                    """, statement -> {
                statement.setInt(1, inserted);
                statement.setLong(2, maximumRevision);
                statement.setObject(3, executionId);
            });
            requireSingleUpdate(updated, "记录数值聚合执行进度");
        }

        /** {@inheritDoc} */
        @Override
        public void completeExecution(
                UUID executionId,
                RunStatus status,
                long lateRowsIgnored,
                String errorSummary
        ) {
            Objects.requireNonNull(executionId, "数值聚合执行标识不能为空");
            Objects.requireNonNull(status, "数值聚合终态不能为空");
            if (lateRowsIgnored < 0) {
                throw new IllegalArgumentException("超窗口迟到行数不得为负数");
            }
            String normalizedError = normalizeError(status, errorSummary);
            int updated = update("""
                    UPDATE telemetry_aggregation_execution
                       SET status = ?,
                           late_rows_ignored = ?,
                           completed_at = CURRENT_TIMESTAMP,
                           last_error = ?
                     WHERE execution_id = ?
                       AND status = 'RUNNING'
                    """, statement -> {
                statement.setString(1, status.name());
                statement.setLong(2, lateRowsIgnored);
                statement.setString(3, normalizedError);
                statement.setObject(4, executionId);
            });
            requireSingleUpdate(updated, "完成数值聚合执行事实");
        }

        /**
         * 校验失败终态必须携带错误，而成功和部分完成不得伪装为失败。
         */
        private String normalizeError(RunStatus status, String errorSummary) {
            if (status == RunStatus.FAILED) {
                return requireText(errorSummary, "数值聚合失败摘要", 2_000);
            }
            if (errorSummary != null) {
                throw new IllegalArgumentException("成功或部分完成的数值聚合不得包含失败摘要");
            }
            return null;
        }

        /**
         * 在一个 JDBC 事务中提交聚合版本和执行进度。
         */
        private int inBatchTransaction(BatchWork work) {
            try {
                connection.setAutoCommit(false);
                int result = work.execute();
                connection.commit();
                connection.setAutoCommit(true);
                return result;
            } catch (SQLException exception) {
                rollbackAndRestore(exception);
                throw storageFailure("无法提交数值聚合原子批次", exception);
            } catch (RuntimeException exception) {
                rollbackAndRestore(exception);
                throw exception;
            }
        }

        /**
         * 回滚失败批次并恢复会话自动提交状态。
         */
        private void rollbackAndRestore(Throwable failure) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            try {
                connection.setAutoCommit(true);
            } catch (SQLException restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
        }

        /**
         * 执行单条数据库更新。
         */
        private int update(String sql, StatementBinder binder) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                binder.bind(statement);
                return statement.executeUpdate();
            } catch (SQLException exception) {
                throw storageFailure("数值聚合数据库写入失败", exception);
            }
        }

        /**
         * 要求数据库写入只影响目标执行事实。
         */
        private void requireSingleUpdate(int updated, String action) {
            if (updated != 1) {
                throw new AggregationStorageException(action + "未影响唯一目标行");
            }
        }

        /**
         * 创建带原因链的聚合存储异常。
         */
        private AggregationStorageException storageFailure(String message, SQLException cause) {
            return new AggregationStorageException(message, cause);
        }
    }

    /**
     * 校验并清理有界文本字段。
     */
    private static String requireText(String value, String label, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(label + "不能为空且长度不能超过" + maximumLength);
        }
        return value.strip();
    }

    /**
     * 数据库锁生命周期内的聚合工作函数。
     *
     * @param <T> 工作结果类型
     */
    @FunctionalInterface
    public interface LockedWork<T> {
        /**
         * 使用锁定会话执行聚合工作。
         */
        T execute(Session session);
    }

    /**
     * 只允许在持有数据库聚合单实例锁期间使用的会话。
     */
    public interface Session {
        /**
         * 创建一条运行中聚合执行事实。
         */
        void startExecution(
                UUID executionId,
                String instanceId,
                int policyCount,
                int ruleCount,
                long sourceWatermark
        );

        /**
         * 原子追加一批聚合版本并增加已提交进度。
         *
         * @return 本次实际插入的版本数量
         */
        int writeRollupBatch(UUID executionId, List<RollupWrite> rollups);

        /**
         * 把运行中事实收敛为不可变终态。
         */
        void completeExecution(
                UUID executionId,
                RunStatus status,
                long lateRowsIgnored,
                String errorSummary
        );
    }

    /**
     * 聚合任务可持久化的终态。
     */
    public enum RunStatus {
        /** 已扫描完本轮全部候选桶。 */
        SUCCEEDED,
        /** 达到本轮有界上限，后续轮次继续。 */
        PARTIAL,
        /** 执行失败并留下脱敏摘要。 */
        FAILED
    }

    /**
     * 待追加的单个版本化数值聚合桶。
     *
     * @param deviceId 设备标识
     * @param productKey 产品稳定键
     * @param propertyId 属性标识
     * @param bucketSeconds 固定桶宽秒数
     * @param bucketStart UTC 对齐桶开始时间
     * @param bucketEnd 右开桶结束时间
     * @param averageValue 平均值
     * @param minimumValue 最小值
     * @param maximumValue 最大值
     * @param sampleCount 样本数量
     * @param worstQuality 最差质量序号
     * @param latestReceivedAt 最晚平台接收时间
     * @param sourceWatermark 源摄取水位
     * @param runtimeRevision 策略运行修订号
     * @param snapshotId 策略快照标识
     * @param policyContentHash 策略内容哈希
     */
    public record RollupWrite(
            UUID deviceId,
            String productKey,
            String propertyId,
            int bucketSeconds,
            Instant bucketStart,
            Instant bucketEnd,
            double averageValue,
            double minimumValue,
            double maximumValue,
            long sampleCount,
            short worstQuality,
            Instant latestReceivedAt,
            long sourceWatermark,
            long runtimeRevision,
            UUID snapshotId,
            String policyContentHash
    ) {
        /**
         * 校验聚合事实只使用稳定键、固定桶宽、有限数值和完整策略证据。
         */
        public RollupWrite {
            Objects.requireNonNull(deviceId, "聚合设备标识不能为空");
            productKey = requireText(productKey, "聚合产品稳定键", 64);
            propertyId = requireText(propertyId, "聚合属性标识", 128);
            if (!PROPERTY_ID.matcher(propertyId).matches()) {
                throw new IllegalArgumentException("聚合属性标识格式非法");
            }
            if (!BUCKET_SECONDS.contains(bucketSeconds)) {
                throw new IllegalArgumentException("聚合桶宽不在固定白名单中");
            }
            Objects.requireNonNull(bucketStart, "聚合桶开始时间不能为空");
            Objects.requireNonNull(bucketEnd, "聚合桶结束时间不能为空");
            if (bucketStart.getNano() != 0
                    || !bucketEnd.equals(bucketStart.plusSeconds(bucketSeconds))
                    || Math.floorMod(bucketStart.getEpochSecond(), bucketSeconds) != 0) {
                throw new IllegalArgumentException("聚合桶必须按 UTC 对齐且结束时间匹配桶宽");
            }
            if (!Double.isFinite(averageValue)
                    || !Double.isFinite(minimumValue)
                    || !Double.isFinite(maximumValue)
                    || minimumValue > averageValue
                    || averageValue > maximumValue) {
                throw new IllegalArgumentException("聚合统计值必须有限且满足最小值、平均值、最大值顺序");
            }
            if (sampleCount < 1 || worstQuality < 0 || worstQuality > 3) {
                throw new IllegalArgumentException("聚合样本数或最差质量等级非法");
            }
            Objects.requireNonNull(latestReceivedAt, "聚合样本最晚接收时间不能为空");
            if (sourceWatermark < 0 || runtimeRevision < 1) {
                throw new IllegalArgumentException("聚合源水位不得为负且策略修订必须为正数");
            }
            Objects.requireNonNull(snapshotId, "聚合策略快照标识不能为空");
            if (policyContentHash == null
                    || !CONTENT_HASH.matcher(policyContentHash).matches()) {
                throw new IllegalArgumentException("聚合策略内容哈希格式非法");
            }
        }
    }

    /**
     * JDBC 参数绑定函数。
     */
    @FunctionalInterface
    private interface StatementBinder {
        /**
         * 绑定预编译语句参数。
         */
        void bind(PreparedStatement statement) throws SQLException;
    }

    /**
     * 单个原子聚合批次的数据库工作。
     */
    @FunctionalInterface
    private interface BatchWork {
        /**
         * 执行聚合版本与进度写入并返回插入数量。
         */
        int execute();
    }

    /**
     * 数值聚合数据库访问失败。
     */
    public static final class AggregationStorageException extends RuntimeException {
        /**
         * 创建无底层异常的聚合存储失败。
         */
        public AggregationStorageException(String message) {
            super(message);
        }

        /**
         * 创建保留底层异常链的聚合存储失败。
         */
        public AggregationStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
