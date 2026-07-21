package com.bosco.mine.telemetry.aggregation;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
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
public final class AggregationExecutionRepository implements AggregationExecutor.Store {

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
    @Override
    public <T> Optional<T> withLock(AggregationExecutor.LockedWork<T> work) {
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
    private static final class JdbcSession implements AggregationExecutor.Session {
        private final Connection connection;

        private JdbcSession(Connection connection) {
            this.connection = connection;
        }

        /** {@inheritDoc} */
        @Override
        public AggregationRecomputeExecutor.Preparation prepareRecomputation(
                String instanceId,
                AggregationRecomputeExecutor.Request request
        ) {
            Objects.requireNonNull(request, "显式聚合重算请求不能为空");
            String normalizedInstance = requireText(instanceId, "数值聚合实例标识", 128);
            return inTransaction(() -> {
                Optional<RecomputeExecutionFact> existing =
                        findRecomputeExecution(request.executionId(), true);
                if (existing.isPresent()) {
                    return prepareExistingRecomputation(existing.orElseThrow(), request);
                }
                long sourceWatermark = loadSourceWatermark();
                Optional<PolicyEvidence> evidence = loadCurrentPolicyEvidence(request, true);
                insertRecomputeExecution(
                        normalizedInstance, request, sourceWatermark, evidence.orElse(null));
                if (evidence.isEmpty()) {
                    return new AggregationRecomputeExecutor.Preparation(
                            AggregationRecomputeExecutor.PreparationStatus.REJECTED,
                            sourceWatermark, 0, "当前聚合策略镜像不存在完全匹配的启用规则");
                }
                return new AggregationRecomputeExecutor.Preparation(
                        AggregationRecomputeExecutor.PreparationStatus.READY,
                        sourceWatermark, 0, null);
            });
        }

        /**
         * 校验并映射已存在的稳定执行事实，不允许用相同执行标识切换请求身份。
         */
        private AggregationRecomputeExecutor.Preparation prepareExistingRecomputation(
                RecomputeExecutionFact execution,
                AggregationRecomputeExecutor.Request request
        ) {
            if (!execution.matches(request)) {
                throw new AggregationStorageException("稳定执行标识对应的显式重算请求身份不一致");
            }
            if ("SUCCEEDED".equals(execution.status())) {
                return new AggregationRecomputeExecutor.Preparation(
                        AggregationRecomputeExecutor.PreparationStatus.SUCCEEDED,
                        execution.sourceWatermark(), execution.bucketCount(), null);
            }
            if ("FAILED".equals(execution.status())) {
                return new AggregationRecomputeExecutor.Preparation(
                        AggregationRecomputeExecutor.PreparationStatus.FAILED,
                        execution.sourceWatermark(), execution.bucketCount(),
                        execution.lastError());
            }
            if (!"RUNNING".equals(execution.status())) {
                return new AggregationRecomputeExecutor.Preparation(
                        AggregationRecomputeExecutor.PreparationStatus.FAILED,
                        execution.sourceWatermark(), execution.bucketCount(),
                        "显式聚合重算执行出现不允许的部分终态");
            }
            if (execution.evidence() == null
                    || !loadCurrentPolicyEvidence(request, true)
                    .filter(execution.evidence()::equals)
                    .isPresent()) {
                return new AggregationRecomputeExecutor.Preparation(
                        AggregationRecomputeExecutor.PreparationStatus.REJECTED,
                        execution.sourceWatermark(), execution.bucketCount(),
                        "当前聚合策略镜像已变化或不再包含目标启用规则");
            }
            return new AggregationRecomputeExecutor.Preparation(
                    AggregationRecomputeExecutor.PreparationStatus.READY,
                    execution.sourceWatermark(), execution.bucketCount(), null);
        }

        /** {@inheritDoc} */
        @Override
        public int recompute(
                AggregationRecomputeExecutor.Request request,
                int limit
        ) {
            Objects.requireNonNull(request, "显式聚合重算请求不能为空");
            if (limit < 1 || limit > 10_000) {
                throw new IllegalArgumentException("显式聚合重算查询上限必须在 1 到 10000 之间");
            }
            return inTransaction(() -> {
                RecomputeExecutionFact execution = findRecomputeExecution(
                        request.executionId(), true).orElseThrow(() ->
                        new AggregationStorageException("显式聚合重算执行事实不存在"));
                if (!execution.matches(request) || !"RUNNING".equals(execution.status())) {
                    throw new AggregationStorageException("显式聚合重算执行身份或状态不允许计算");
                }
                if (execution.evidence() == null
                        || !loadCurrentPolicyEvidence(request, true)
                        .filter(execution.evidence()::equals)
                        .isPresent()) {
                    throw new AggregationStorageException(
                            "当前聚合策略镜像已变化或不再包含目标启用规则");
                }
                List<RollupWrite> rollups = selectRecomputeRollups(execution, request, limit);
                return writeRollupsInCurrentTransaction(request.executionId(), rollups);
            });
        }

        /**
         * 读取并锁定稳定执行事实，保证身份校验与后续进度提交属于同一事务。
         */
        private Optional<RecomputeExecutionFact> findRecomputeExecution(
                UUID executionId,
                boolean lock
        ) {
            String sql = """
                    SELECT execution_id, execution_kind, recompute_request_id,
                           target_device_id, target_property_id, target_bucket_seconds,
                           requested_from, requested_to, status, source_watermark,
                           bucket_count, runtime_revision, snapshot_id,
                           policy_content_hash, last_error
                      FROM telemetry_aggregation_execution
                     WHERE execution_id = ?
                    """ + (lock ? " FOR UPDATE" : "");
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setObject(1, executionId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) return Optional.empty();
                    Long runtimeRevision = (Long) resultSet.getObject("runtime_revision");
                    PolicyEvidence evidence = runtimeRevision == null ? null : new PolicyEvidence(
                            runtimeRevision,
                            resultSet.getObject("snapshot_id", UUID.class),
                            resultSet.getString("policy_content_hash"));
                    return Optional.of(new RecomputeExecutionFact(
                            resultSet.getObject("execution_id", UUID.class),
                            resultSet.getString("execution_kind"),
                            resultSet.getObject("recompute_request_id", UUID.class),
                            resultSet.getObject("target_device_id", UUID.class),
                            resultSet.getString("target_property_id"),
                            (Integer) resultSet.getObject("target_bucket_seconds"),
                            nullableInstant(resultSet, "requested_from"),
                            nullableInstant(resultSet, "requested_to"),
                            resultSet.getString("status"),
                            resultSet.getLong("source_watermark"),
                            resultSet.getInt("bucket_count"),
                            evidence,
                            resultSet.getString("last_error")));
                }
            } catch (SQLException exception) {
                throw storageFailure("无法读取显式聚合重算执行事实", exception);
            }
        }

        /**
         * 读取固定原始摄取水位。
         */
        private long loadSourceWatermark() {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT committed_ingest_id
                      FROM telemetry_ingestion_watermark
                     WHERE singleton_id = 1
                    """); ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new AggregationStorageException("原始遥测摄取水位单例不存在");
                }
                return resultSet.getLong(1);
            } catch (SQLException exception) {
                throw storageFailure("无法固定显式重算源水位", exception);
            }
        }

        /**
         * 锁定并读取当前完全匹配的启用策略证据。
         */
        private Optional<PolicyEvidence> loadCurrentPolicyEvidence(
                AggregationRecomputeExecutor.Request request,
                boolean lock
        ) {
            String sql = """
                    SELECT policy.runtime_revision, policy.snapshot_id, policy.content_hash
                      FROM telemetry_aggregation_policy policy
                     WHERE policy.device_id = ?
                       AND policy.enabled
                       AND EXISTS (
                           SELECT 1
                             FROM jsonb_array_elements(
                                 policy.values_json -> 'policy' -> 'rules'
                             ) AS rule(value)
                            WHERE rule.value ->> 'propertyId' = ?
                              AND (rule.value ->> 'bucketSeconds')::INTEGER = ?
                       )
                    """ + (lock ? " FOR SHARE OF policy" : "");
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setObject(1, request.deviceId());
                statement.setString(2, request.propertyId());
                statement.setLong(3, request.bucket().toSeconds());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) return Optional.empty();
                    return Optional.of(new PolicyEvidence(
                            resultSet.getLong("runtime_revision"),
                            resultSet.getObject("snapshot_id", UUID.class),
                            resultSet.getString("content_hash")));
                }
            } catch (SQLException exception) {
                throw storageFailure("无法校验显式重算当前聚合策略", exception);
            }
        }

        /**
         * 创建携带不可变请求身份和单策略证据的显式重算执行事实。
         */
        private void insertRecomputeExecution(
                String instanceId,
                AggregationRecomputeExecutor.Request request,
                long sourceWatermark,
                PolicyEvidence evidence
        ) {
            int policyCount = evidence == null ? 0 : 1;
            int updated = update("""
                    INSERT INTO telemetry_aggregation_execution (
                        execution_id, instance_id, status, policy_count, rule_count,
                        source_watermark, execution_kind, recompute_request_id,
                        target_device_id, target_property_id, target_bucket_seconds,
                        requested_from, requested_to, runtime_revision, snapshot_id,
                        policy_content_hash
                    ) VALUES (
                        ?, ?, 'RUNNING', ?, ?, ?, 'RECOMPUTE', ?, ?, ?, ?, ?, ?, ?, ?, ?
                    )
                    """, statement -> {
                statement.setObject(1, request.executionId());
                statement.setString(2, instanceId);
                statement.setInt(3, policyCount);
                statement.setInt(4, policyCount);
                statement.setLong(5, sourceWatermark);
                statement.setObject(6, request.requestId());
                statement.setObject(7, request.deviceId());
                statement.setString(8, request.propertyId());
                statement.setLong(9, request.bucket().toSeconds());
                statement.setTimestamp(10, Timestamp.from(request.from()));
                statement.setTimestamp(11, Timestamp.from(request.to()));
                if (evidence == null) {
                    statement.setObject(12, null);
                    statement.setObject(13, null);
                    statement.setObject(14, null);
                } else {
                    statement.setLong(12, evidence.runtimeRevision());
                    statement.setObject(13, evidence.snapshotId());
                    statement.setString(14, evidence.contentHash());
                }
            });
            requireSingleUpdate(updated, "创建显式聚合重算执行事实");
        }

        /**
         * 查询固定水位下请求范围内全部可见数值样本，不应用普通迟到修正窗口。
         */
        private List<RollupWrite> selectRecomputeRollups(
                RecomputeExecutionFact execution,
                AggregationRecomputeExecutor.Request request,
                int limit
        ) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    WITH samples AS MATERIALIZED (
                        SELECT numeric.*,
                               to_timestamp(
                                   floor(extract(epoch FROM numeric.time) / ?)
                                   * ?
                               ) AS bucket_start
                          FROM telemetry_numeric numeric
                         WHERE numeric.device_id = ?
                           AND numeric.property_id = ?
                           AND numeric.time >= ?
                           AND numeric.time < ?
                           AND numeric.ingest_id <= ?
                           AND numeric.value_double IS NOT NULL
                    ), candidates AS MATERIALIZED (
                        SELECT samples.bucket_start
                          FROM samples
                         WHERE NOT EXISTS (
                               SELECT 1
                                 FROM telemetry_numeric_rollup rollup
                                WHERE rollup.execution_id = ?
                                  AND rollup.device_id = ?
                                  AND rollup.property_id = ?
                                  AND rollup.bucket_seconds = ?
                                  AND rollup.bucket_start = samples.bucket_start
                           )
                         GROUP BY samples.bucket_start
                         ORDER BY samples.bucket_start
                         LIMIT ?
                    )
                    SELECT ?::UUID AS device_id,
                           (array_agg(
                               samples.product_key
                               ORDER BY samples.time DESC, samples.ingest_id DESC
                           ))[1] AS product_key,
                           ? AS property_id,
                           ? AS bucket_seconds,
                           candidates.bucket_start,
                           candidates.bucket_start + make_interval(secs => ?) AS bucket_end,
                           AVG(samples.value_double) AS average_value,
                           MIN(samples.value_double) AS minimum_value,
                           MAX(samples.value_double) AS maximum_value,
                           COUNT(*) AS sample_count,
                           MAX(samples.quality) AS worst_quality,
                           MAX(samples.received_at) AS latest_received_at,
                           ? AS source_watermark,
                           ? AS runtime_revision,
                           ?::UUID AS snapshot_id,
                           ? AS content_hash
                      FROM candidates
                      JOIN samples ON samples.bucket_start = candidates.bucket_start
                     GROUP BY candidates.bucket_start
                     ORDER BY candidates.bucket_start
                    """)) {
                int bucketSeconds = Math.toIntExact(request.bucket().toSeconds());
                statement.setInt(1, bucketSeconds);
                statement.setInt(2, bucketSeconds);
                statement.setObject(3, request.deviceId());
                statement.setString(4, request.propertyId());
                statement.setTimestamp(5, Timestamp.from(request.from()));
                statement.setTimestamp(6, Timestamp.from(request.to()));
                statement.setLong(7, execution.sourceWatermark());
                statement.setObject(8, request.executionId());
                statement.setObject(9, request.deviceId());
                statement.setString(10, request.propertyId());
                statement.setInt(11, bucketSeconds);
                statement.setInt(12, limit);
                statement.setObject(13, request.deviceId());
                statement.setString(14, request.propertyId());
                statement.setInt(15, bucketSeconds);
                statement.setInt(16, bucketSeconds);
                statement.setLong(17, execution.sourceWatermark());
                statement.setLong(18, execution.evidence().runtimeRevision());
                statement.setObject(19, execution.evidence().snapshotId());
                statement.setString(20, execution.evidence().contentHash());
                try (ResultSet resultSet = statement.executeQuery()) {
                    java.util.ArrayList<RollupWrite> rollups = new java.util.ArrayList<>();
                    while (resultSet.next()) rollups.add(mapRollup(resultSet));
                    return List.copyOf(rollups);
                }
            } catch (SQLException exception) {
                throw storageFailure("无法查询显式重算数值聚合桶", exception);
            }
        }

        /**
         * 把显式重算版本和执行进度提交到调用方已经开启的同一事务。
         */
        private int writeRollupsInCurrentTransaction(
                UUID executionId,
                List<RollupWrite> rollups
        ) {
            int inserted = 0;
            long maximumRevision = 0;
            for (RollupWrite rollup : rollups) {
                long revision = insertRollup(executionId, rollup);
                if (revision > 0) {
                    inserted = Math.incrementExact(inserted);
                    maximumRevision = Math.max(maximumRevision, revision);
                }
            }
            if (inserted > 0) checkpointExecution(executionId, inserted, maximumRevision);
            return inserted;
        }

        private static Instant nullableInstant(ResultSet resultSet, String column)
                throws SQLException {
            Timestamp value = resultSet.getTimestamp(column);
            return value == null ? null : value.toInstant();
        }

        /** {@inheritDoc} */
        @Override
        public int recoverStaleExecutions(Duration timeout) {
            long seconds;
            try {
                seconds = timeout.toSeconds();
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("数值聚合失联超时过大", exception);
            }
            if (seconds < 1) {
                throw new IllegalArgumentException("数值聚合失联超时必须至少一秒");
            }
            return update("""
                    UPDATE telemetry_aggregation_execution
                       SET status = 'FAILED',
                           completed_at = CURRENT_TIMESTAMP,
                           last_error = '运行实例超过失联超时，已由后续执行收敛'
                     WHERE status = 'RUNNING'
                       AND started_at < CURRENT_TIMESTAMP - make_interval(secs => ?)
                    """, statement -> statement.setLong(1, seconds));
        }

        /** {@inheritDoc} */
        @Override
        public AggregationExecutor.Plan loadPlan() {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT watermark.committed_ingest_id,
                           COALESCE((
                               SELECT MAX(execution.source_watermark)
                                 FROM telemetry_aggregation_execution execution
                                WHERE execution.status = 'SUCCEEDED'
                           ), 0) AS previous_success_watermark,
                           (SELECT COUNT(*)
                              FROM telemetry_aggregation_policy policy
                             WHERE policy.enabled) AS policy_count,
                           COALESCE((
                               SELECT SUM(jsonb_array_length(
                                   policy.values_json -> 'policy' -> 'rules'))
                                 FROM telemetry_aggregation_policy policy
                                WHERE policy.enabled
                           ), 0) AS rule_count
                      FROM telemetry_ingestion_watermark watermark
                     WHERE watermark.singleton_id = 1
                    """); ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new AggregationStorageException("原始遥测摄取水位单例不存在");
                }
                return new AggregationExecutor.Plan(
                        resultSet.getLong("committed_ingest_id"),
                        resultSet.getLong("previous_success_watermark"),
                        Math.toIntExact(resultSet.getLong("policy_count")),
                        Math.toIntExact(resultSet.getLong("rule_count"))
                );
            } catch (SQLException exception) {
                throw storageFailure("无法固定数值聚合执行计划", exception);
            }
        }

        /** {@inheritDoc} */
        @Override
        public void startExecution(
                UUID executionId,
                String instanceId,
                AggregationExecutor.Plan executionPlan
        ) {
            Objects.requireNonNull(executionId, "数值聚合执行标识不能为空");
            Objects.requireNonNull(executionPlan, "数值聚合执行计划不能为空");
            String normalizedInstance = requireText(instanceId, "数值聚合实例标识", 128);
            int updated = update("""
                    INSERT INTO telemetry_aggregation_execution (
                        execution_id, instance_id, status, policy_count,
                        rule_count, source_watermark
                    ) VALUES (?, ?, 'RUNNING', ?, ?, ?)
                    """, statement -> {
                statement.setObject(1, executionId);
                statement.setString(2, normalizedInstance);
                statement.setInt(3, executionPlan.policyCount());
                statement.setInt(4, executionPlan.ruleCount());
                statement.setLong(5, executionPlan.sourceWatermark());
            });
            requireSingleUpdate(updated, "创建数值聚合执行事实");
        }

        /** {@inheritDoc} */
        @Override
        public int aggregateNextBatch(UUID executionId, int limit) {
            Objects.requireNonNull(executionId, "数值聚合执行标识不能为空");
            if (limit < 1 || limit > 10_000) {
                throw new IllegalArgumentException("数值聚合查询批次必须在 1 到 10000 之间");
            }
            return writeRollupBatch(executionId, selectRollups(executionId, limit));
        }

        /**
         * 在单条数据库快照查询中选取需要首次计算或迟到修正的下一批闭合桶。
         */
        private List<RollupWrite> selectRollups(UUID executionId, int limit) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    WITH execution AS (
                        SELECT execution_id, source_watermark, started_at
                          FROM telemetry_aggregation_execution
                         WHERE execution_id = ?
                           AND status = 'RUNNING'
                    ), rules AS (
                        SELECT policy.device_id,
                               policy.runtime_revision,
                               policy.snapshot_id,
                               policy.content_hash,
                               policy.correction_window_seconds,
                               (rule.value ->> 'propertyId') AS property_id,
                               (rule.value ->> 'bucketSeconds')::INTEGER AS bucket_seconds
                          FROM telemetry_aggregation_policy policy
                          CROSS JOIN LATERAL jsonb_array_elements(
                              policy.values_json -> 'policy' -> 'rules'
                          ) AS rule(value)
                         WHERE policy.enabled
                    ), raw_buckets AS (
                        SELECT DISTINCT
                               numeric.device_id,
                               rules.property_id,
                               rules.bucket_seconds,
                               rules.runtime_revision,
                               rules.snapshot_id,
                               rules.content_hash,
                               rules.correction_window_seconds,
                               to_timestamp(
                                   floor(extract(epoch FROM numeric.time)
                                       / rules.bucket_seconds)
                                   * rules.bucket_seconds
                               ) AS bucket_start,
                               to_timestamp(
                                   (floor(extract(epoch FROM numeric.time)
                                       / rules.bucket_seconds) + 1)
                                   * rules.bucket_seconds
                               ) AS bucket_end
                          FROM telemetry_numeric numeric
                          JOIN rules
                            ON rules.device_id = numeric.device_id
                           AND rules.property_id = numeric.property_id
                          CROSS JOIN execution
                         WHERE numeric.ingest_id <= execution.source_watermark
                           AND numeric.value_double IS NOT NULL
                           AND to_timestamp(
                               (floor(extract(epoch FROM numeric.time)
                                   / rules.bucket_seconds) + 1)
                               * rules.bucket_seconds
                           ) <= execution.started_at
                           AND numeric.received_at <=
                               to_timestamp(
                                   (floor(extract(epoch FROM numeric.time)
                                       / rules.bucket_seconds) + 1)
                                   * rules.bucket_seconds
                               ) + make_interval(
                                   secs => rules.correction_window_seconds)
                    ), candidates AS MATERIALIZED (
                        SELECT raw_buckets.*
                          FROM raw_buckets
                          CROSS JOIN execution
                          LEFT JOIN LATERAL (
                              SELECT rollup.source_watermark
                                FROM telemetry_numeric_rollup rollup
                                JOIN telemetry_aggregation_execution completed
                                  ON completed.execution_id = rollup.execution_id
                               WHERE rollup.device_id = raw_buckets.device_id
                                 AND rollup.property_id = raw_buckets.property_id
                                 AND rollup.bucket_seconds = raw_buckets.bucket_seconds
                                 AND rollup.bucket_start = raw_buckets.bucket_start
                                 AND rollup.runtime_revision = raw_buckets.runtime_revision
                                 AND rollup.snapshot_id = raw_buckets.snapshot_id
                                 AND rollup.policy_content_hash = raw_buckets.content_hash
                                 AND completed.status IN ('SUCCEEDED', 'PARTIAL')
                               ORDER BY rollup.source_watermark DESC,
                                        rollup.rollup_revision DESC
                               LIMIT 1
                          ) latest ON TRUE
                         WHERE NOT EXISTS (
                               SELECT 1
                                 FROM telemetry_numeric_rollup current_rollup
                                WHERE current_rollup.execution_id = execution.execution_id
                                  AND current_rollup.device_id = raw_buckets.device_id
                                  AND current_rollup.property_id = raw_buckets.property_id
                                  AND current_rollup.bucket_seconds = raw_buckets.bucket_seconds
                                  AND current_rollup.bucket_start = raw_buckets.bucket_start
                           )
                           AND (
                               latest.source_watermark IS NULL
                               OR EXISTS (
                                   SELECT 1
                                     FROM telemetry_numeric changed
                                    WHERE changed.device_id = raw_buckets.device_id
                                      AND changed.property_id = raw_buckets.property_id
                                      AND changed.time >= raw_buckets.bucket_start
                                      AND changed.time < raw_buckets.bucket_end
                                      AND changed.ingest_id > latest.source_watermark
                                      AND changed.ingest_id <= execution.source_watermark
                                      AND changed.value_double IS NOT NULL
                                      AND changed.received_at <= raw_buckets.bucket_end
                                          + make_interval(secs =>
                                              raw_buckets.correction_window_seconds)
                               )
                           )
                         ORDER BY raw_buckets.bucket_start,
                                  raw_buckets.device_id,
                                  raw_buckets.property_id,
                                  raw_buckets.bucket_seconds
                         LIMIT ?
                    )
                    SELECT candidates.device_id,
                           (array_agg(
                               numeric.product_key
                               ORDER BY numeric.time DESC, numeric.ingest_id DESC
                           ))[1] AS product_key,
                           candidates.property_id,
                           candidates.bucket_seconds,
                           candidates.bucket_start,
                           candidates.bucket_end,
                           AVG(numeric.value_double) AS average_value,
                           MIN(numeric.value_double) AS minimum_value,
                           MAX(numeric.value_double) AS maximum_value,
                           COUNT(*) AS sample_count,
                           MAX(numeric.quality) AS worst_quality,
                           MAX(numeric.received_at) AS latest_received_at,
                           execution.source_watermark,
                           candidates.runtime_revision,
                           candidates.snapshot_id,
                           candidates.content_hash
                      FROM candidates
                      CROSS JOIN execution
                      JOIN telemetry_numeric numeric
                        ON numeric.device_id = candidates.device_id
                       AND numeric.property_id = candidates.property_id
                       AND numeric.time >= candidates.bucket_start
                       AND numeric.time < candidates.bucket_end
                     WHERE numeric.ingest_id <= execution.source_watermark
                       AND numeric.value_double IS NOT NULL
                       AND numeric.received_at <= candidates.bucket_end
                           + make_interval(secs => candidates.correction_window_seconds)
                     GROUP BY candidates.device_id,
                              candidates.property_id,
                              candidates.bucket_seconds,
                              candidates.bucket_start,
                              candidates.bucket_end,
                              execution.source_watermark,
                              candidates.runtime_revision,
                              candidates.snapshot_id,
                              candidates.content_hash
                     ORDER BY candidates.bucket_start,
                              candidates.device_id,
                              candidates.property_id,
                              candidates.bucket_seconds
                    """)) {
                statement.setObject(1, executionId);
                statement.setInt(2, limit);
                try (ResultSet resultSet = statement.executeQuery()) {
                    java.util.ArrayList<RollupWrite> rollups = new java.util.ArrayList<>();
                    while (resultSet.next()) {
                        rollups.add(mapRollup(resultSet));
                    }
                    return List.copyOf(rollups);
                }
            } catch (SQLException exception) {
                throw storageFailure("无法查询下一批闭合数值聚合桶", exception);
            }
        }

        /**
         * 把数据库聚合结果映射为经过应用层二次校验的追加事实。
         */
        private RollupWrite mapRollup(ResultSet resultSet) throws SQLException {
            return new RollupWrite(
                    resultSet.getObject("device_id", UUID.class),
                    resultSet.getString("product_key"),
                    resultSet.getString("property_id"),
                    resultSet.getInt("bucket_seconds"),
                    resultSet.getTimestamp("bucket_start").toInstant(),
                    resultSet.getTimestamp("bucket_end").toInstant(),
                    resultSet.getDouble("average_value"),
                    resultSet.getDouble("minimum_value"),
                    resultSet.getDouble("maximum_value"),
                    resultSet.getLong("sample_count"),
                    resultSet.getShort("worst_quality"),
                    resultSet.getTimestamp("latest_received_at").toInstant(),
                    resultSet.getLong("source_watermark"),
                    resultSet.getLong("runtime_revision"),
                    resultSet.getObject("snapshot_id", UUID.class),
                    resultSet.getString("content_hash")
            );
        }

        /** {@inheritDoc} */
        @Override
        public long countIgnoredLateRows(
                UUID executionId,
                AggregationExecutor.Plan executionPlan
        ) {
            Objects.requireNonNull(executionId, "数值聚合执行标识不能为空");
            Objects.requireNonNull(executionPlan, "数值聚合执行计划不能为空");
            try (PreparedStatement statement = connection.prepareStatement("""
                    WITH execution AS (
                        SELECT started_at
                          FROM telemetry_aggregation_execution
                         WHERE execution_id = ?
                           AND status = 'RUNNING'
                    ), rules AS (
                        SELECT policy.device_id,
                               policy.correction_window_seconds,
                               (rule.value ->> 'propertyId') AS property_id,
                               (rule.value ->> 'bucketSeconds')::INTEGER AS bucket_seconds
                          FROM telemetry_aggregation_policy policy
                          CROSS JOIN LATERAL jsonb_array_elements(
                              policy.values_json -> 'policy' -> 'rules'
                          ) AS rule(value)
                         WHERE policy.enabled
                    )
                    SELECT COUNT(DISTINCT (
                               numeric.time, numeric.event_id, numeric.property_id
                           ))
                      FROM telemetry_numeric numeric
                      JOIN rules
                        ON rules.device_id = numeric.device_id
                       AND rules.property_id = numeric.property_id
                      CROSS JOIN execution
                     WHERE numeric.ingest_id > ?
                       AND numeric.ingest_id <= ?
                       AND numeric.value_double IS NOT NULL
                       AND to_timestamp(
                           (floor(extract(epoch FROM numeric.time)
                               / rules.bucket_seconds) + 1)
                           * rules.bucket_seconds
                       ) <= execution.started_at
                       AND numeric.received_at >
                           to_timestamp(
                               (floor(extract(epoch FROM numeric.time)
                                   / rules.bucket_seconds) + 1)
                               * rules.bucket_seconds
                           ) + make_interval(secs => rules.correction_window_seconds)
                    """)) {
                statement.setObject(1, executionId);
                statement.setLong(2, executionPlan.previousSuccessWatermark());
                statement.setLong(3, executionPlan.sourceWatermark());
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    return resultSet.getLong(1);
                }
            } catch (SQLException exception) {
                throw storageFailure("无法统计超窗口迟到原始遥测", exception);
            }
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
            return inTransaction(() ->
                    writeRollupsInCurrentTransaction(executionId, immutableRollups));
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
                AggregationExecutor.RunStatus status,
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
        private String normalizeError(
                AggregationExecutor.RunStatus status,
                String errorSummary
        ) {
            if (status == AggregationExecutor.RunStatus.FAILED) {
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
        private <T> T inTransaction(TransactionWork<T> work) {
            try {
                connection.setAutoCommit(false);
                T result = work.execute();
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

        /**
         * 当前启用规则的不可变策略证据。
         */
        private record PolicyEvidence(
                long runtimeRevision,
                UUID snapshotId,
                String contentHash
        ) {
        }

        /**
         * 显式重算执行事实中用于稳定重放的不可变身份和进度。
         */
        private record RecomputeExecutionFact(
                UUID executionId,
                String executionKind,
                UUID requestId,
                UUID deviceId,
                String propertyId,
                Integer bucketSeconds,
                Instant from,
                Instant to,
                String status,
                long sourceWatermark,
                int bucketCount,
                PolicyEvidence evidence,
                String lastError
        ) {
            private boolean matches(AggregationRecomputeExecutor.Request request) {
                return "RECOMPUTE".equals(executionKind)
                        && executionId.equals(request.executionId())
                        && Objects.equals(requestId, request.requestId())
                        && Objects.equals(deviceId, request.deviceId())
                        && Objects.equals(propertyId, request.propertyId())
                        && Objects.equals(bucketSeconds,
                        Math.toIntExact(request.bucket().toSeconds()))
                        && Objects.equals(from, request.from())
                        && Objects.equals(to, request.to());
            }
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
    private interface TransactionWork<T> {
        /**
         * 执行同一聚合事务中的工作并返回结果。
         */
        T execute();
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
