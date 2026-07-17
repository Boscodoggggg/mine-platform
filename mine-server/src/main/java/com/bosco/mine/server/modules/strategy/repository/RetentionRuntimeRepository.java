package com.bosco.mine.server.modules.strategy.repository;

import com.bosco.mine.server.modules.device.infrastructure.TelemetryQueryConnection;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * 使用遥测专用只读连接分批统计保留影响并查询不可变执行事实。
 */
@Repository
public class RetentionRuntimeRepository {

    private static final int DEVICE_BATCH_SIZE = 1_000;

    private final ObjectProvider<TelemetryQueryConnection> connectionProvider;
    private final JdbcTemplate fixedJdbcTemplate;
    private final TransactionTemplate fixedTransaction;

    /**
     * 创建生产环境保留运行查询仓储。
     *
     * @param connectionProvider 可选的遥测只读连接提供者
     */
    @Autowired
    public RetentionRuntimeRepository(
            ObjectProvider<TelemetryQueryConnection> connectionProvider
    ) {
        this.connectionProvider = Objects.requireNonNull(
                connectionProvider, "遥测只读连接提供者不能为空");
        this.fixedJdbcTemplate = null;
        this.fixedTransaction = null;
    }

    /**
     * 创建使用指定 JDBC 模板的容器集成测试仓储。
     *
     * @param jdbcTemplate 遥测数据库 JDBC 模板
     */
    public RetentionRuntimeRepository(JdbcTemplate jdbcTemplate) {
        this.connectionProvider = null;
        this.fixedJdbcTemplate = Objects.requireNonNull(
                jdbcTemplate, "遥测 JDBC 模板不能为空");
        DataSource dataSource = Objects.requireNonNull(
                jdbcTemplate.getDataSource(), "遥测 JDBC 模板必须配置数据源");
        this.fixedTransaction = readOnlyTransaction(dataSource);
    }

    /**
     * 在同一个数据库可重复读快照中统计两个受控数据集的过期候选。
     *
     * @param deviceIds          受影响设备标识
     * @param rawTelemetryDays   原始数值遥测保留天数
     * @param qualityEvidenceDays 质量证据保留天数
     * @param countLimit         每个数据集最大精确统计行数
     * @return 数据库统计时点和两个数据集影响
     */
    public PreviewSnapshot preview(
            List<UUID> deviceIds,
            int rawTelemetryDays,
            int qualityEvidenceDays,
            long countLimit
    ) {
        validatePreview(deviceIds, rawTelemetryDays, qualityEvidenceDays, countLimit);
        List<UUID> stableDeviceIds = List.copyOf(deviceIds);
        return executeReadOnly(jdbcTemplate -> {
            Instant observedAt = databaseNow(jdbcTemplate);
            if (stableDeviceIds.isEmpty()) {
                DatasetImpact empty = new DatasetImpact(0, null, false);
                return new PreviewSnapshot(observedAt, empty, empty);
            }
            DatasetImpact rawTelemetry = summarize(
                    jdbcTemplate,
                    stableDeviceIds,
                    observedAt.minus(rawTelemetryDays, ChronoUnit.DAYS),
                    countLimit,
                    Dataset.RAW_TELEMETRY
            );
            DatasetImpact qualityEvidence = summarize(
                    jdbcTemplate,
                    stableDeviceIds,
                    observedAt.minus(qualityEvidenceDays, ChronoUnit.DAYS),
                    countLimit,
                    Dataset.QUALITY_EVIDENCE
            );
            return new PreviewSnapshot(observedAt, rawTelemetry, qualityEvidence);
        });
    }

    /**
     * 查询数据库最近的数据保留执行事实。
     *
     * @param limit 最大返回数量
     * @return 按开始时间倒序排列的执行事实
     */
    public List<ExecutionRecord> findExecutions(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("数据保留执行查询数量必须在 1 到 100 之间");
        }
        return executeReadOnly(jdbcTemplate -> List.copyOf(jdbcTemplate.query(
                """
                SELECT execution_id, instance_id, status, policy_count,
                       raw_rows_deleted, quality_rows_deleted, batch_count,
                       started_at, completed_at, last_error
                  FROM telemetry_retention_execution
                 ORDER BY started_at DESC, execution_id DESC
                 LIMIT ?
                """,
                (resultSet, rowNumber) -> new ExecutionRecord(
                        resultSet.getObject("execution_id", UUID.class),
                        resultSet.getString("instance_id"),
                        resultSet.getString("status"),
                        resultSet.getInt("policy_count"),
                        resultSet.getLong("raw_rows_deleted"),
                        resultSet.getLong("quality_rows_deleted"),
                        resultSet.getInt("batch_count"),
                        resultSet.getTimestamp("started_at").toInstant(),
                        nullableInstant(resultSet.getTimestamp("completed_at")),
                        resultSet.getString("last_error")
                ),
                limit
        )));
    }

    private DatasetImpact summarize(
            JdbcTemplate jdbcTemplate,
            List<UUID> deviceIds,
            Instant cutoff,
            long countLimit,
            Dataset dataset
    ) {
        long eligibleRows = 0;
        Instant earliestEligibleAt = null;
        boolean atLeast = false;
        NamedParameterJdbcTemplate namedJdbcTemplate =
                new NamedParameterJdbcTemplate(jdbcTemplate);
        for (int offset = 0; offset < deviceIds.size(); offset += DEVICE_BATCH_SIZE) {
            List<UUID> batch = deviceIds.subList(
                    offset, Math.min(offset + DEVICE_BATCH_SIZE, deviceIds.size()));
            long remaining = Math.max(0, countLimit - eligibleRows);
            int sampleLimit = Math.toIntExact(Math.min(Integer.MAX_VALUE,
                    remaining == 0 ? 1 : remaining + 1));
            BatchImpact impact = queryBatch(
                    namedJdbcTemplate, batch, cutoff, sampleLimit, dataset);
            earliestEligibleAt = earlier(earliestEligibleAt, impact.earliestEligibleAt());
            if (eligibleRows < countLimit) {
                long accepted = Math.min(impact.rows(), countLimit - eligibleRows);
                eligibleRows += accepted;
                if (impact.rows() > accepted) {
                    atLeast = true;
                }
            } else if (impact.rows() > 0) {
                atLeast = true;
            }
        }
        return new DatasetImpact(eligibleRows, earliestEligibleAt, atLeast);
    }

    private BatchImpact queryBatch(
            NamedParameterJdbcTemplate jdbcTemplate,
            List<UUID> deviceIds,
            Instant cutoff,
            int sampleLimit,
            Dataset dataset
    ) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("deviceIds", deviceIds)
                .addValue("cutoff", Timestamp.from(cutoff))
                .addValue("sampleLimit", sampleLimit);
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
                dataset.query(),
                parameters,
                (resultSet, rowNumber) -> new BatchImpact(
                        resultSet.getLong("row_count"),
                        nullableInstant(resultSet.getTimestamp("earliest_time"))
                )
        ), "数据保留预览批次查询不得返回空结果");
    }

    private <T> T executeReadOnly(Function<JdbcTemplate, T> query) {
        if (fixedJdbcTemplate != null) {
            return Objects.requireNonNull(
                    fixedTransaction.execute(status -> query.apply(fixedJdbcTemplate)),
                    "数据保留只读事务不得返回空结果");
        }
        TelemetryQueryConnection connection = connectionProvider.getIfAvailable();
        if (connection == null) {
            throw new IllegalStateException("遥测查询能力未启用");
        }
        return Objects.requireNonNull(
                connection.readOnlyTransaction().execute(
                        status -> query.apply(connection.jdbcTemplate())),
                "数据保留只读事务不得返回空结果");
    }

    private TransactionTemplate readOnlyTransaction(DataSource dataSource) {
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return transaction;
    }

    private Instant databaseNow(JdbcTemplate jdbcTemplate) {
        Timestamp timestamp = jdbcTemplate.queryForObject(
                "SELECT CURRENT_TIMESTAMP", Timestamp.class);
        return Objects.requireNonNull(timestamp, "数据库统计时间不能为空").toInstant();
    }

    private Instant nullableInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Instant earlier(Instant current, Instant candidate) {
        if (current == null) {
            return candidate;
        }
        if (candidate == null) {
            return current;
        }
        return candidate.isBefore(current) ? candidate : current;
    }

    private void validatePreview(
            List<UUID> deviceIds,
            int rawTelemetryDays,
            int qualityEvidenceDays,
            long countLimit
    ) {
        if (deviceIds == null || deviceIds.stream().anyMatch(Objects::isNull)
                || rawTelemetryDays < 7 || rawTelemetryDays > 3_650
                || qualityEvidenceDays < rawTelemetryDays
                || qualityEvidenceDays > 3_650
                || countLimit < 1 || countLimit > Integer.MAX_VALUE - 1L) {
            throw new IllegalArgumentException("数据保留预览参数超出安全范围");
        }
    }

    private enum Dataset {
        RAW_TELEMETRY("""
                SELECT COUNT(*) AS row_count, MIN(candidate_time) AS earliest_time
                  FROM (
                    SELECT time AS candidate_time
                      FROM telemetry_numeric
                     WHERE device_id IN (:deviceIds)
                       AND time < :cutoff
                       AND received_at < :cutoff
                     ORDER BY time, event_id, property_id
                     LIMIT :sampleLimit
                  ) candidate
                """),
        QUALITY_EVIDENCE("""
                SELECT COUNT(*) AS row_count, MIN(candidate_time) AS earliest_time
                  FROM (
                    SELECT created_at AS candidate_time
                      FROM telemetry_quality_evidence
                     WHERE device_id IN (:deviceIds)
                       AND created_at < :cutoff
                     ORDER BY created_at, event_id, property_id, rule_code
                     LIMIT :sampleLimit
                  ) candidate
                """);

        private final String query;

        Dataset(String query) {
            this.query = query;
        }

        private String query() {
            return query;
        }
    }

    private record BatchImpact(long rows, Instant earliestEligibleAt) {
    }

    /**
     * 数据保留影响统计快照。
     *
     * @param observedAt      数据库统计时点
     * @param rawTelemetry    原始数值遥测影响
     * @param qualityEvidence 质量证据影响
     */
    public record PreviewSnapshot(
            Instant observedAt,
            DatasetImpact rawTelemetry,
            DatasetImpact qualityEvidence
    ) {
    }

    /**
     * 单个数据集的有界影响统计。
     *
     * @param eligibleRows      达到删除年龄的行数，封顶后返回上限值
     * @param earliestEligibleAt 最早达到删除条件的数据时间
     * @param atLeast           是否存在超过返回行数的更多候选
     */
    public record DatasetImpact(
            long eligibleRows,
            Instant earliestEligibleAt,
            boolean atLeast
    ) {
    }

    /**
     * 数据保留执行事实只读记录。
     *
     * @param executionId         执行标识
     * @param instanceId          Telemetry 实例标识
     * @param status              执行状态
     * @param policyCount         启用策略数量
     * @param rawRowsDeleted      原始遥测删除行数
     * @param qualityRowsDeleted  质量证据删除行数
     * @param batchCount          已提交批次数
     * @param startedAt           开始时间
     * @param completedAt         完成时间，运行中为空
     * @param lastError           失败时的脱敏摘要
     */
    public record ExecutionRecord(
            UUID executionId,
            String instanceId,
            String status,
            int policyCount,
            long rawRowsDeleted,
            long qualityRowsDeleted,
            int batchCount,
            Instant startedAt,
            Instant completedAt,
            String lastError
    ) {
    }
}
