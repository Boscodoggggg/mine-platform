package com.bosco.mine.telemetry.aggregation;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用真实 TimescaleDB 校验闭合桶计算、窗口内修正、超窗口排除和失联恢复。
 */
@Testcontainers
class AggregationExecutorIT {

    private static final DockerImageName TIMESCALE_IMAGE = DockerImageName
            .parse("timescale/timescaledb:2.28.2-pg18-oss@sha256:"
                    + "8ed8d7336f00a3a90bb6fe709d506172012c7e673e0cf71465491d06e2db5fbd")
            .asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer TIMESCALE = new PostgreSQLContainer(TIMESCALE_IMAGE)
            .withDatabaseName("mine_telemetry")
            .withUsername("mine_ts")
            .withPassword("mine-timescale-test-password");

    private static final UUID DEVICE_ID = uuid(901);
    private static final UUID PRODUCT_ID = uuid(902);
    private static final UUID SNAPSHOT_ID = uuid(903);
    private static final UUID UPDATED_SNAPSHOT_ID = uuid(905);
    private static final UUID STALE_EXECUTION_ID = uuid(904);
    private static final String CONTENT_HASH = "sha256:" + "d".repeat(64);
    private static final String UPDATED_CONTENT_HASH = "sha256:" + "e".repeat(64);
    private static DataSource dataSource;
    private static JdbcTemplate jdbcTemplate;
    private static AggregationExecutor executor;

    @BeforeAll
    static void 初始化数值聚合数据库() {
        dataSource = new DriverManagerDataSource(
                TIMESCALE.getJdbcUrl(), TIMESCALE.getUsername(), TIMESCALE.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
        executor = new AggregationExecutor(
                new AggregationExecutionRepository(dataSource),
                "telemetry-aggregation-it",
                10,
                3,
                Duration.ofHours(2)
        );
    }

    @Test
    void 应聚合闭合桶并追加窗口内迟到修正版且排除超窗口迟到值() {
        Instant base = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        Instant oldBucket = base.minus(15, ChronoUnit.MINUTES);
        Instant recentBucket = base.minus(2, ChronoUnit.MINUTES);
        Instant openBucket = base;
        insertPolicy();
        insertRaw(uuid(911), 100, oldBucket.plusSeconds(10), oldBucket.plusSeconds(20), 1000D);
        insertRaw(uuid(912), 101, oldBucket.plusSeconds(20), base.minusSeconds(300), 1600D);
        insertRaw(uuid(913), 102, recentBucket.plusSeconds(10),
                recentBucket.plusSeconds(20), 1200D);
        insertRaw(uuid(914), 103, openBucket.plusSeconds(10), base.plusSeconds(10), 1800D);
        setWatermark(103);
        jdbcTemplate.update("""
                INSERT INTO telemetry_aggregation_execution (
                    execution_id, instance_id, status, policy_count,
                    rule_count, source_watermark, started_at
                ) VALUES (?, 'stale-aggregation', 'RUNNING', 1, 1, 0,
                    CURRENT_TIMESTAMP - INTERVAL '3 hours')
                """, STALE_EXECUTION_ID);

        AggregationExecutor.RunResult first = executor.runOnce();

        assertThat(first.status()).isEqualTo(AggregationExecutor.RunStatus.SUCCEEDED);
        assertThat(first.bucketCount()).isEqualTo(2);
        assertThat(first.lateRowsIgnored()).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM telemetry_aggregation_execution
                 WHERE execution_id = ?
                """, String.class, STALE_EXECUTION_ID)).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForList("""
                SELECT bucket_start, average_value, sample_count
                  FROM telemetry_numeric_rollup
                 WHERE device_id = ?
                   AND property_id = 'engine.rpm'
                 ORDER BY bucket_start
                """, DEVICE_ID))
                .containsExactly(
                        Map.of(
                                "bucket_start", Timestamp.from(oldBucket),
                                "average_value", 1000D,
                                "sample_count", 1L
                        ),
                        Map.of(
                                "bucket_start", Timestamp.from(recentBucket),
                                "average_value", 1200D,
                                "sample_count", 1L
                        )
                );

        insertRaw(uuid(915), 104, recentBucket.plusSeconds(40), Instant.now(), 1400D);
        setWatermark(104);

        AggregationExecutor.RunResult corrected = executor.runOnce();

        assertThat(corrected.status()).isEqualTo(AggregationExecutor.RunStatus.SUCCEEDED);
        assertThat(corrected.bucketCount()).isOne();
        assertThat(corrected.lateRowsIgnored()).isZero();
        assertThat(jdbcTemplate.queryForList("""
                SELECT average_value, sample_count, source_watermark
                  FROM telemetry_numeric_rollup
                 WHERE device_id = ?
                   AND property_id = 'engine.rpm'
                   AND bucket_start = ?
                 ORDER BY rollup_revision
                """, DEVICE_ID, Timestamp.from(recentBucket)))
                .containsExactly(
                        Map.of(
                                "average_value", 1200D,
                                "sample_count", 1L,
                                "source_watermark", 103L
                        ),
                        Map.of(
                                "average_value", 1300D,
                                "sample_count", 2L,
                                "source_watermark", 104L
                        )
                );
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM telemetry_numeric_rollup
                 WHERE device_id = ?
                   AND bucket_start = ?
                """, Long.class, DEVICE_ID, Timestamp.from(openBucket))).isZero();

        jdbcTemplate.update("""
                UPDATE telemetry_aggregation_policy
                   SET runtime_revision = 8,
                       snapshot_id = ?,
                       content_hash = ?,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE device_id = ?
                """, UPDATED_SNAPSHOT_ID, UPDATED_CONTENT_HASH, DEVICE_ID);

        AggregationExecutor.RunResult strategyRecalculation = executor.runOnce();

        assertThat(strategyRecalculation.status())
                .isEqualTo(AggregationExecutor.RunStatus.SUCCEEDED);
        assertThat(strategyRecalculation.bucketCount()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList("""
                SELECT runtime_revision, snapshot_id, policy_content_hash, sample_count
                  FROM telemetry_numeric_rollup
                 WHERE device_id = ?
                   AND bucket_start = ?
                 ORDER BY rollup_revision DESC
                 LIMIT 1
                """, DEVICE_ID, Timestamp.from(recentBucket)))
                .containsExactly(Map.of(
                        "runtime_revision", 8L,
                        "snapshot_id", UPDATED_SNAPSHOT_ID,
                        "policy_content_hash", UPDATED_CONTENT_HASH,
                        "sample_count", 2L
                ));
    }

    private static void insertPolicy() {
        jdbcTemplate.update("""
                INSERT INTO telemetry_aggregation_policy (
                    device_id, product_id, runtime_revision, snapshot_id,
                    values_json, content_hash
                ) VALUES (?, ?, 7, ?, CAST(? AS JSONB), ?)
                """, DEVICE_ID, PRODUCT_ID, SNAPSHOT_ID, """
                {"policy":{
                  "enabled":true,
                  "correctionWindowSeconds":300,
                  "rules":[{"propertyId":"engine.rpm","bucketSeconds":60}]
                }}
                """, CONTENT_HASH);
    }

    private static void insertRaw(
            UUID eventId,
            long ingestId,
            Instant effectiveAt,
            Instant receivedAt,
            double value
    ) {
        jdbcTemplate.update("""
                INSERT INTO telemetry_numeric (
                    time, occurred_at, device_id, product_key, property_id,
                    value_double, quality, event_id, received_at, ingest_id
                ) VALUES (?, ?, ?, 'haul-truck', 'engine.rpm', ?, 0, ?, ?, ?)
                """, Timestamp.from(effectiveAt), Timestamp.from(effectiveAt), DEVICE_ID,
                value, eventId, Timestamp.from(receivedAt), ingestId);
    }

    private static void setWatermark(long ingestId) {
        jdbcTemplate.update("""
                UPDATE telemetry_ingestion_watermark
                   SET committed_ingest_id = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE singleton_id = 1
                """, ingestId);
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("019f6f00-0000-7000-8000-%012d".formatted(suffix));
    }
}
