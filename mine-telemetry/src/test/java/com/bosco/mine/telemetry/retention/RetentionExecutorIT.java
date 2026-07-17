package com.bosco.mine.telemetry.retention;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
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
                """, Long.class, ENABLED_DEVICE_ID)).isOne();
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

    private static void insertPolicy(UUID deviceId, boolean enabled) {
        jdbcTemplate.update("""
                INSERT INTO telemetry_retention_policy (
                    device_id, product_id, runtime_revision, snapshot_id,
                    values_json, content_hash
                ) VALUES (?, ?, 1, ?, CAST(? AS JSONB), ?)
                """, deviceId, PRODUCT_ID, uuid(deviceId.equals(ENABLED_DEVICE_ID) ? 631 : 632),
                """
                {"policy":{
                  "enabled":%s,
                  "retentionClass":"STANDARD",
                  "rawTelemetryDays":90,
                  "qualityEvidenceDays":180
                }}
                """.formatted(enabled), "sha256:" + "a".repeat(64));
    }

    private static void insertRaw(UUID eventId, UUID deviceId, long ingestId, String age) {
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
                """, age, age, deviceId, eventId, age, ingestId);
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
