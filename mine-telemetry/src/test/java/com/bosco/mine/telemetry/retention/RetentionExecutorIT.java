package com.bosco.mine.telemetry.retention;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 TimescaleDB 校验保留任务的数据库锁、有界删除、保护边界和执行事实。
 */
@Testcontainers
class RetentionExecutorIT {

    private static final DockerImageName TIMESCALE_IMAGE = DockerImageName
            .parse("timescale/timescaledb:2.28.2-pg18-oss@sha256:"
                    + "8ed8d7336f00a3a90bb6fe709d506172012c7e673e0cf71465491d06e2db5fbd")
            .asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer TIMESCALE = new PostgreSQLContainer(TIMESCALE_IMAGE)
            .withDatabaseName("mine_telemetry")
            .withUsername("mine_ts")
            .withPassword("mine-timescale-test-password");

    private static final UUID PRODUCT_ID = uuid(601);
    private static final UUID ENABLED_DEVICE_ID = uuid(602);
    private static final UUID DISABLED_DEVICE_ID = uuid(603);
    private static final UUID UNMANAGED_DEVICE_ID = uuid(604);
    private static final UUID STALE_EXECUTION_ID = uuid(605);
    private static final UUID ROLLBACK_DEVICE_ID = uuid(606);
    private static final UUID COVERED_DEVICE_ID = uuid(651);
    private static final UUID MISSING_DEVICE_ID = uuid(652);
    private static final UUID FAILED_DEVICE_ID = uuid(653);
    private static final UUID OLD_VERSION_DEVICE_ID = uuid(654);
    private static final UUID CORRECTION_WINDOW_DEVICE_ID = uuid(655);
    private static final UUID UNRULED_PROPERTY_DEVICE_ID = uuid(656);
    private static final UUID AGGREGATION_SNAPSHOT_ID = uuid(657);
    private static final UUID OLD_AGGREGATION_SNAPSHOT_ID = uuid(658);
    private static final String AGGREGATION_HASH = "sha256:" + "c".repeat(64);
    private static final String OLD_AGGREGATION_HASH = "sha256:" + "d".repeat(64);
    private static DataSource dataSource;
    private static JdbcTemplate jdbcTemplate;
    private static RetentionExecutor executor;

    @BeforeAll
    static void 初始化数据保留数据库() {
        dataSource = new DriverManagerDataSource(
                TIMESCALE.getJdbcUrl(), TIMESCALE.getUsername(), TIMESCALE.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
        executor = new RetentionExecutor(
                new RetentionExecutionRepository(dataSource),
                "telemetry-retention-it",
                1,
                1,
                Duration.ofHours(2)
        );
    }

    @Test
    void 应有界清理启用策略的过期数据并保护其他运行事实() {
        insertPolicy(ENABLED_DEVICE_ID, true);
        insertPolicy(DISABLED_DEVICE_ID, false);
        insertRaw(uuid(611), ENABLED_DEVICE_ID, 1, "100 days");
        insertRaw(uuid(612), ENABLED_DEVICE_ID, 2, "10 days");
        insertRaw(uuid(613), DISABLED_DEVICE_ID, 3, "100 days");
        insertRaw(uuid(614), UNMANAGED_DEVICE_ID, 4, "100 days");
        insertRaw(uuid(615), ENABLED_DEVICE_ID, 5, "100 days", "10 days");
        insertRaw(uuid(616), ENABLED_DEVICE_ID, 6, "10 days", "100 days");
        insertInbox(uuid(611), ENABLED_DEVICE_ID, 1);
        insertQuality(uuid(621), ENABLED_DEVICE_ID, "200 days");
        insertQuality(uuid(622), ENABLED_DEVICE_ID, "100 days");
        insertQuality(uuid(623), DISABLED_DEVICE_ID, "200 days");
        insertQuality(uuid(624), UNMANAGED_DEVICE_ID, "200 days");
        jdbcTemplate.update("""
                INSERT INTO telemetry_retention_execution (
                    execution_id, instance_id, status, policy_count, started_at
                ) VALUES (?, 'stale-instance', 'RUNNING', 1,
                    CURRENT_TIMESTAMP - INTERVAL '3 hours')
                """, STALE_EXECUTION_ID);

        RetentionExecutor.RunResult first = executor.runOnce();
        RetentionExecutor.RunResult second = executor.runOnce();

        assertThat(first.status()).isEqualTo(RetentionExecutor.RunStatus.PARTIAL);
        assertThat(first.rawRowsDeleted()).isOne();
        assertThat(first.qualityRowsDeleted()).isOne();
        assertThat(second.status()).isEqualTo(RetentionExecutor.RunStatus.SUCCEEDED);
        assertThat(second.rawRowsDeleted()).isZero();
        assertThat(second.qualityRowsDeleted()).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM telemetry_numeric
                 WHERE device_id = ?
                """, Long.class, ENABLED_DEVICE_ID)).isEqualTo(3L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM telemetry_numeric
                 WHERE device_id IN (?, ?)
                """, Long.class, DISABLED_DEVICE_ID, UNMANAGED_DEVICE_ID)).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM telemetry_quality_evidence
                 WHERE device_id = ?
                """, Long.class, ENABLED_DEVICE_ID)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM telemetry_quality_evidence
                 WHERE device_id IN (?, ?)
                """, Long.class, DISABLED_DEVICE_ID, UNMANAGED_DEVICE_ID)).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM telemetry_inbox WHERE event_id = ?",
                Long.class, uuid(611))).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM telemetry_retention_execution WHERE execution_id = ?",
                String.class, STALE_EXECUTION_ID)).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForList("""
                SELECT status, raw_rows_deleted, quality_rows_deleted, batch_count
                  FROM telemetry_retention_execution
                 WHERE execution_id IN (?, ?)
                 ORDER BY started_at
                """, first.executionId(), second.executionId()))
                .containsExactly(
                        Map.of(
                                "status", "PARTIAL",
                                "raw_rows_deleted", 1L,
                                "quality_rows_deleted", 1L,
                                "batch_count", 2
                        ),
                        Map.of(
                                "status", "SUCCEEDED",
                                "raw_rows_deleted", 0L,
                                "quality_rows_deleted", 0L,
                                "batch_count", 0
                        )
                );
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM telemetry_retention_execution WHERE execution_id = ?",
                first.executionId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("数据保留执行事实禁止删除");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE telemetry_retention_execution
                   SET raw_rows_deleted = raw_rows_deleted + 1
                 WHERE execution_id = ?
                """, first.executionId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("数据保留终态事实禁止修改");
    }

    @Test
    void 其他会话持有数据库锁时应无副作用跳过() throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement lock = connection.prepareStatement(
                     "SELECT pg_advisory_lock(hashtextextended('mine:telemetry:retention', 0))");
             PreparedStatement unlock = connection.prepareStatement(
                     "SELECT pg_advisory_unlock(hashtextextended('mine:telemetry:retention', 0))")) {
            lock.execute();

            RetentionExecutor.RunResult result = executor.runOnce();

            assertThat(result.status()).isEqualTo(RetentionExecutor.RunStatus.SKIPPED_LOCKED);
            assertThat(result.executionId()).isNull();
            unlock.execute();
        }
    }

    @Test
    void 非默认自动提交连接仍应持久化执行终态() {
        DataSource manualCommitDataSource = new DelegatingDataSource(dataSource) {
            @Override
            public Connection getConnection() throws SQLException {
                Connection connection = super.getConnection();
                connection.setAutoCommit(false);
                return connection;
            }
        };
        RetentionExecutor manualCommitExecutor = new RetentionExecutor(
                new RetentionExecutionRepository(manualCommitDataSource),
                "telemetry-manual-commit-it", 10, 1, Duration.ofHours(2));

        RetentionExecutor.RunResult result = manualCommitExecutor.runOnce();

        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM telemetry_retention_execution
                 WHERE execution_id = ?
                """, String.class, result.executionId())).isEqualTo(result.status().name());
    }

    @Test
    void 聚合覆盖缺失失败旧版本或未过修正窗口时应阻止原始数据清理() {
        Instant oldBucket = Instant.now().minus(100, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.MINUTES);
        Instant correctionBucket = Instant.now().truncatedTo(ChronoUnit.MINUTES)
                .minus(7, ChronoUnit.DAYS);
        List<UUID> managedDevices = List.of(
                COVERED_DEVICE_ID,
                MISSING_DEVICE_ID,
                FAILED_DEVICE_ID,
                OLD_VERSION_DEVICE_ID,
                CORRECTION_WINDOW_DEVICE_ID,
                UNRULED_PROPERTY_DEVICE_ID
        );
        managedDevices.forEach(deviceId -> insertPolicy(deviceId, true, 7));
        insertAggregationPolicy(COVERED_DEVICE_ID, 2, "engine.rpm", 60, 300);
        insertAggregationPolicy(MISSING_DEVICE_ID, 2, "engine.rpm", 60, 300);
        insertAggregationPolicy(FAILED_DEVICE_ID, 2, "engine.rpm", 60, 300);
        insertAggregationPolicy(OLD_VERSION_DEVICE_ID, 2, "engine.rpm", 60, 300);
        insertAggregationPolicy(
                CORRECTION_WINDOW_DEVICE_ID, 2, "engine.rpm", 60, 604800);
        insertAggregationPolicy(
                UNRULED_PROPERTY_DEVICE_ID, 2, "hydraulic.pressure", 60, 300);

        insertRaw(uuid(671), COVERED_DEVICE_ID, 201, oldBucket, "engine.rpm");
        insertRaw(uuid(672), MISSING_DEVICE_ID, 202, oldBucket, "engine.rpm");
        insertRaw(uuid(673), FAILED_DEVICE_ID, 203, oldBucket, "engine.rpm");
        insertRaw(uuid(674), OLD_VERSION_DEVICE_ID, 204, oldBucket, "engine.rpm");
        insertRaw(uuid(675), CORRECTION_WINDOW_DEVICE_ID, 205,
                correctionBucket, "engine.rpm");
        insertRaw(uuid(676), UNRULED_PROPERTY_DEVICE_ID, 206,
                oldBucket, "engine.rpm");
        insertRollup(COVERED_DEVICE_ID, 201, oldBucket, 2,
                AGGREGATION_SNAPSHOT_ID, AGGREGATION_HASH, "SUCCEEDED", uuid(681));
        insertRollup(FAILED_DEVICE_ID, 203, oldBucket, 2,
                AGGREGATION_SNAPSHOT_ID, AGGREGATION_HASH, "FAILED", uuid(682));
        insertRollup(OLD_VERSION_DEVICE_ID, 204, oldBucket, 1,
                OLD_AGGREGATION_SNAPSHOT_ID, OLD_AGGREGATION_HASH,
                "SUCCEEDED", uuid(683));
        insertRollup(CORRECTION_WINDOW_DEVICE_ID, 205, correctionBucket, 2,
                AGGREGATION_SNAPSHOT_ID, AGGREGATION_HASH, "SUCCEEDED", uuid(684));
        RetentionExecutor coverageExecutor = new RetentionExecutor(
                new RetentionExecutionRepository(dataSource),
                "telemetry-retention-coverage-it", 100, 3, Duration.ofHours(2));

        RetentionExecutor.RunResult blocked = coverageExecutor.runOnce();

        assertThat(blocked.aggregationBlockedRows()).isEqualTo(4);
        assertThat(rawExists(uuid(671))).isFalse();
        assertThat(rawExists(uuid(676))).isFalse();
        assertThat(rawExists(uuid(672))).isTrue();
        assertThat(rawExists(uuid(673))).isTrue();
        assertThat(rawExists(uuid(674))).isTrue();
        assertThat(rawExists(uuid(675))).isTrue();

        insertRollup(MISSING_DEVICE_ID, 202, oldBucket, 2,
                AGGREGATION_SNAPSHOT_ID, AGGREGATION_HASH, "SUCCEEDED", uuid(685));
        insertRollup(FAILED_DEVICE_ID, 203, oldBucket, 2,
                AGGREGATION_SNAPSHOT_ID, AGGREGATION_HASH, "PARTIAL", uuid(686));
        insertRollup(OLD_VERSION_DEVICE_ID, 204, oldBucket, 2,
                AGGREGATION_SNAPSHOT_ID, AGGREGATION_HASH, "SUCCEEDED", uuid(687));

        RetentionExecutor.RunResult covered = coverageExecutor.runOnce();

        assertThat(covered.aggregationBlockedRows()).isOne();
        assertThat(rawExists(uuid(672))).isFalse();
        assertThat(rawExists(uuid(673))).isFalse();
        assertThat(rawExists(uuid(674))).isFalse();
        assertThat(rawExists(uuid(675))).isTrue();
    }

    @Test
    void 进度写入失败时应回滚同批原始遥测删除() {
        UUID executionId = uuid(607);
        UUID eventId = uuid(617);
        insertPolicy(ROLLBACK_DEVICE_ID, true);
        insertRaw(eventId, ROLLBACK_DEVICE_ID, 7, "100 days");
        RetentionExecutionRepository repository = new RetentionExecutionRepository(dataSource);

        assertThatThrownBy(() -> repository.withLock(session -> {
            session.startExecution(executionId, "telemetry-rollback-it", 1);
            jdbcTemplate.update("""
                    UPDATE telemetry_retention_execution
                       SET status = 'FAILED', completed_at = CURRENT_TIMESTAMP,
                           last_error = '测试进度写入失败'
                     WHERE execution_id = ?
                    """, executionId);
            session.deleteRawTelemetryBatch(executionId, 10, 0, 0, 0);
            return true;
        })).isInstanceOf(RuntimeException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM telemetry_numeric WHERE event_id = ?",
                Long.class, eventId)).isOne();
        jdbcTemplate.update(
                "DELETE FROM telemetry_numeric WHERE event_id = ?", eventId);
        jdbcTemplate.update(
                "DELETE FROM telemetry_retention_policy WHERE device_id = ?",
                ROLLBACK_DEVICE_ID);
    }

    private static void insertPolicy(UUID deviceId, boolean enabled) {
        insertPolicy(deviceId, enabled, 90);
    }

    private static void insertPolicy(UUID deviceId, boolean enabled, int rawTelemetryDays) {
        String retentionClass = rawTelemetryDays <= 30 ? "SHORT" : "STANDARD";
        jdbcTemplate.update("""
                INSERT INTO telemetry_retention_policy (
                    device_id, product_id, runtime_revision, snapshot_id,
                    values_json, content_hash
                ) VALUES (?, ?, 1, ?, CAST(? AS JSONB), ?)
                """, deviceId, PRODUCT_ID, uuid(deviceId.equals(ENABLED_DEVICE_ID) ? 631 : 632),
                """
                {"policy":{
                  "enabled":%s,
                  "retentionClass":"%s",
                  "rawTelemetryDays":%d,
                  "qualityEvidenceDays":180
                }}
                """.formatted(enabled, retentionClass, rawTelemetryDays),
                "sha256:" + "a".repeat(64));
    }

    private static void insertRaw(UUID eventId, UUID deviceId, long ingestId, String age) {
        insertRaw(eventId, deviceId, ingestId, age, age);
    }

    private static void insertRaw(
            UUID eventId,
            UUID deviceId,
            long ingestId,
            String timeAge,
            String receivedAge
    ) {
        jdbcTemplate.update("""
                INSERT INTO telemetry_numeric (
                    time, occurred_at, device_id, product_key, property_id, value_double,
                    quality, event_id, received_at, ingest_id
                ) VALUES (
                    CURRENT_TIMESTAMP - CAST(? AS INTERVAL),
                    CURRENT_TIMESTAMP - CAST(? AS INTERVAL),
                    ?, 'haul-truck', 'engine.rpm', 1200, 0, ?,
                    CURRENT_TIMESTAMP - CAST(? AS INTERVAL), ?
                )
                """, timeAge, timeAge, deviceId, eventId, receivedAge, ingestId);
    }

    private static void insertRaw(
            UUID eventId,
            UUID deviceId,
            long ingestId,
            Instant effectiveAt,
            String propertyId
    ) {
        jdbcTemplate.update("""
                INSERT INTO telemetry_numeric (
                    time, occurred_at, device_id, product_key, property_id, value_double,
                    quality, event_id, received_at, ingest_id
                ) VALUES (?, ?, ?, 'haul-truck', ?, 1200, 0, ?, ?, ?)
                """, Timestamp.from(effectiveAt), Timestamp.from(effectiveAt),
                deviceId, propertyId, eventId, Timestamp.from(effectiveAt), ingestId);
    }

    private static void insertAggregationPolicy(
            UUID deviceId,
            long runtimeRevision,
            String propertyId,
            int bucketSeconds,
            int correctionWindowSeconds
    ) {
        jdbcTemplate.update("""
                INSERT INTO telemetry_aggregation_policy (
                    device_id, product_id, runtime_revision, snapshot_id,
                    values_json, content_hash
                ) VALUES (?, ?, ?, ?, CAST(? AS JSONB), ?)
                """, deviceId, PRODUCT_ID, runtimeRevision, AGGREGATION_SNAPSHOT_ID, """
                {"policy":{
                  "enabled":true,
                  "correctionWindowSeconds":%d,
                  "rules":[{"propertyId":"%s","bucketSeconds":%d}]
                }}
                """.formatted(correctionWindowSeconds, propertyId, bucketSeconds),
                AGGREGATION_HASH);
    }

    private static void insertRollup(
            UUID deviceId,
            long sourceWatermark,
            Instant bucketStart,
            long runtimeRevision,
            UUID snapshotId,
            String contentHash,
            String status,
            UUID executionId
    ) {
        jdbcTemplate.update("""
                INSERT INTO telemetry_aggregation_execution (
                    execution_id, instance_id, status, policy_count, rule_count,
                    source_watermark, bucket_count, rollup_watermark,
                    started_at, completed_at, last_error
                ) VALUES (?, 'retention-coverage-it', ?, 1, 1, ?, 1, 1,
                    CURRENT_TIMESTAMP - INTERVAL '1 second', CURRENT_TIMESTAMP,
                    CASE WHEN ? = 'FAILED' THEN '测试聚合失败' ELSE NULL END)
                """, executionId, status, sourceWatermark, status);
        jdbcTemplate.update("""
                INSERT INTO telemetry_numeric_rollup (
                    device_id, product_key, property_id, bucket_seconds,
                    bucket_start, bucket_end, average_value, minimum_value,
                    maximum_value, sample_count, worst_quality, latest_received_at,
                    source_watermark, runtime_revision, snapshot_id,
                    policy_content_hash, execution_id
                ) VALUES (?, 'haul-truck', 'engine.rpm', 60, ?, ?,
                    1200, 1200, 1200, 1, 0, ?, ?, ?, ?, ?, ?)
                """, deviceId, Timestamp.from(bucketStart),
                Timestamp.from(bucketStart.plusSeconds(60)),
                Timestamp.from(bucketStart),
                sourceWatermark, runtimeRevision, snapshotId, contentHash, executionId);
    }

    private static boolean rawExists(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM telemetry_numeric WHERE event_id = ?)",
                Boolean.class, eventId);
    }

    private static void insertInbox(UUID eventId, UUID deviceId, long ingestId) {
        jdbcTemplate.update("""
                INSERT INTO telemetry_inbox (
                    event_id, device_id, message_type, received_at, processed_at, ingest_id
                ) VALUES (?, ?, 'TELEMETRY',
                    CURRENT_TIMESTAMP - INTERVAL '100 days', CURRENT_TIMESTAMP, ?)
                """, eventId, deviceId, ingestId);
    }

    private static void insertQuality(UUID eventId, UUID deviceId, String age) {
        jdbcTemplate.update("""
                INSERT INTO telemetry_quality_evidence (
                    event_id, device_id, property_id, rule_code, result_quality,
                    observed_value, threshold_value, policy_runtime_revision,
                    policy_snapshot_id, policy_content_hash, created_at
                ) VALUES (
                    ?, ?, 'engine.rpm', 'ABOVE_MAXIMUM', 2, 1800, 1600,
                    1, ?, ?, CURRENT_TIMESTAMP - CAST(? AS INTERVAL)
                )
                """, eventId, deviceId, uuid(641), "sha256:" + "b".repeat(64), age);
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("019f6e00-0000-7000-8000-%012d".formatted(suffix));
    }
}
