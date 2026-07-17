package com.bosco.mine.server.modules.strategy;

import com.bosco.mine.server.modules.strategy.repository.RetentionRuntimeRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用真实 TimescaleDB 校验保留预览的分批、统计上限和执行事实只读查询。
 */
@Testcontainers
class RetentionRuntimeRepositoryIT {

    private static final DockerImageName TIMESCALE_IMAGE = DockerImageName
            .parse("timescale/timescaledb:2.28.2-pg18-oss@sha256:"
                    + "8ed8d7336f00a3a90bb6fe709d506172012c7e673e0cf71465491d06e2db5fbd")
            .asCompatibleSubstituteFor("postgres");
    private static final UUID FIRST_DEVICE =
            UUID.fromString("019f6e20-0000-7000-8000-000000000521");
    private static final UUID LAST_DEVICE =
            UUID.fromString("019f6e20-0000-7000-8000-000000000522");

    @Container
    static final PostgreSQLContainer TIMESCALE = new PostgreSQLContainer(TIMESCALE_IMAGE)
            .withDatabaseName("mine_telemetry")
            .withUsername("mine_ts")
            .withPassword("mine-timescale-test-password");

    private static JdbcTemplate jdbcTemplate;
    private static RetentionRuntimeRepository repository;

    @BeforeAll
    static void 创建只读查询仓储() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                TIMESCALE.getJdbcUrl(), TIMESCALE.getUsername(), TIMESCALE.getPassword()
        );
        Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:" + telemetryMigrationDirectory())
                .load()
                .migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new RetentionRuntimeRepository(jdbcTemplate);
    }

    @BeforeEach
    void 清空样本() {
        jdbcTemplate.update("TRUNCATE TABLE telemetry_numeric, telemetry_quality_evidence");
        jdbcTemplate.update("DELETE FROM telemetry_retention_execution WHERE false");
    }

    @Test
    void 应按设备分批并对两个数据集独立封顶且保留真实最早时间() {
        Instant now = databaseNow();
        Instant rawEarliest = now.minus(200, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Instant qualityEarliest = now.minus(220, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        List<UUID> deviceIds = new ArrayList<>();
        deviceIds.add(FIRST_DEVICE);
        for (int index = 0; index < 999; index++) {
            deviceIds.add(UUID.randomUUID());
        }
        deviceIds.add(LAST_DEVICE);
        insertRaw(FIRST_DEVICE, rawEarliest, 1);
        insertRaw(FIRST_DEVICE, now.minus(150, ChronoUnit.DAYS), 2);
        insertRaw(LAST_DEVICE, now.minus(120, ChronoUnit.DAYS), 3);
        insertRaw(LAST_DEVICE, now.minus(20, ChronoUnit.DAYS), 4);
        insertRaw(LAST_DEVICE, now.minus(300, ChronoUnit.DAYS), now, 5);
        insertQuality(FIRST_DEVICE, qualityEarliest, 11);
        insertQuality(LAST_DEVICE, now.minus(210, ChronoUnit.DAYS), 12);
        insertQuality(LAST_DEVICE, now.minus(20, ChronoUnit.DAYS), 13);

        RetentionRuntimeRepository.PreviewSnapshot preview =
                repository.preview(deviceIds, 90, 180, 2);

        assertThat(preview.observedAt()).isBetween(now.minusSeconds(1), databaseNow().plusSeconds(1));
        assertThat(preview.rawTelemetry())
                .isEqualTo(new RetentionRuntimeRepository.DatasetImpact(2, rawEarliest, true));
        assertThat(preview.qualityEvidence())
                .isEqualTo(new RetentionRuntimeRepository.DatasetImpact(2, qualityEarliest, false));
        assertThat(repository.preview(deviceIds, 90, 180, 10).rawTelemetry().eligibleRows())
                .as("设备声明时间很旧但平台刚接收的数据不得进入删除预览")
                .isEqualTo(3);
    }

    @Test
    void 应按数据库时间返回最近执行事实且不伪造运行终态() {
        UUID succeededId = UUID.randomUUID();
        UUID runningId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO telemetry_retention_execution (
                    execution_id, instance_id, status, policy_count,
                    raw_rows_deleted, quality_rows_deleted, batch_count,
                    started_at, completed_at
                ) VALUES (?, 'telemetry-a', 'SUCCEEDED', 2, 10, 3, 2,
                          CURRENT_TIMESTAMP - INTERVAL '2 minutes',
                          CURRENT_TIMESTAMP - INTERVAL '1 minute')
                """, succeededId);
        jdbcTemplate.update("""
                INSERT INTO telemetry_retention_execution (
                    execution_id, instance_id, status, policy_count,
                    raw_rows_deleted, quality_rows_deleted, batch_count, started_at
                ) VALUES (?, 'telemetry-b', 'RUNNING', 3, 5, 1, 1, CURRENT_TIMESTAMP)
                """, runningId);

        List<RetentionRuntimeRepository.ExecutionRecord> executions =
                repository.findExecutions(2);

        assertThat(executions).extracting(RetentionRuntimeRepository.ExecutionRecord::executionId)
                .containsExactly(runningId, succeededId);
        assertThat(executions.getFirst().status()).isEqualTo("RUNNING");
        assertThat(executions.getFirst().completedAt()).isNull();
        assertThat(executions.get(1).rawRowsDeleted()).isEqualTo(10);
    }

    private void insertRaw(UUID deviceId, Instant time, int suffix) {
        insertRaw(deviceId, time, time.plusSeconds(1), suffix);
    }

    private void insertRaw(UUID deviceId, Instant time, Instant receivedAt, int suffix) {
        jdbcTemplate.update("""
                INSERT INTO telemetry_numeric (
                    time, device_id, product_key, property_id, value_double,
                    quality, event_id, received_at, occurred_at, ingest_id
                ) VALUES (?, ?, 'haul-truck', 'engine.rpm', 1, 0, ?, ?, ?, ?)
                """,
                Timestamp.from(time), deviceId,
                UUID.nameUUIDFromBytes(("raw-" + suffix).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                Timestamp.from(receivedAt), Timestamp.from(time), (long) suffix
        );
    }

    private void insertQuality(UUID deviceId, Instant createdAt, int suffix) {
        jdbcTemplate.update("""
                INSERT INTO telemetry_quality_evidence (
                    event_id, device_id, property_id, rule_code, result_quality,
                    observed_value, threshold_value, policy_runtime_revision,
                    policy_snapshot_id, policy_content_hash, created_at
                ) VALUES (?, ?, 'engine.rpm', 'ABOVE_MAXIMUM', 2,
                          101, 100, 1, ?, ?, ?)
                """,
                UUID.nameUUIDFromBytes(("quality-event-" + suffix)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                deviceId,
                UUID.nameUUIDFromBytes(("quality-snapshot-" + suffix)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "sha256:" + "a".repeat(64),
                Timestamp.from(createdAt)
        );
    }

    private Instant databaseNow() {
        return jdbcTemplate.queryForObject(
                "SELECT CURRENT_TIMESTAMP", Timestamp.class).toInstant();
    }

    private static Path telemetryMigrationDirectory() {
        Path current = Path.of("").toAbsolutePath().normalize();
        Path fromRoot = current.resolve("mine-telemetry/src/main/resources/db/migration");
        Path fromModule = current.resolveSibling("mine-telemetry/src/main/resources/db/migration");
        Path directory = Files.isDirectory(fromRoot) ? fromRoot : fromModule;
        assertThat(directory).isDirectory();
        return directory;
    }
}
