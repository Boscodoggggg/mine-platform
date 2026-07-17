package com.bosco.mine.telemetry.aggregation;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用真实 TimescaleDB 校验显式重算固定水位、超迟到样本、版本追加和策略失效关闭。
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AggregationRecomputeExecutorIT {

    private static final DockerImageName TIMESCALE_IMAGE = DockerImageName
            .parse("timescale/timescaledb:2.28.2-pg18-oss@sha256:"
                    + "8ed8d7336f00a3a90bb6fe709d506172012c7e673e0cf71465491d06e2db5fbd")
            .asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer TIMESCALE = new PostgreSQLContainer(TIMESCALE_IMAGE)
            .withDatabaseName("mine_telemetry")
            .withUsername("mine_ts")
            .withPassword("mine-timescale-test-password");

    private static final UUID DEVICE_ID = uuid(821);
    private static final UUID PRODUCT_ID = uuid(822);
    private static final UUID SNAPSHOT_ID = uuid(823);
    private static final UUID UPDATED_SNAPSHOT_ID = uuid(824);
    private static final String CONTENT_HASH = "sha256:" + "8".repeat(64);
    private static final String UPDATED_CONTENT_HASH = "sha256:" + "9".repeat(64);
    private static final Instant FROM = Instant.parse("2026-07-17T10:00:00Z");
    private static final Instant TO = Instant.parse("2026-07-17T10:05:00Z");
    private static DataSource dataSource;
    private static JdbcTemplate jdbcTemplate;
    private static AggregationExecutionRepository repository;
    private static AggregationRecomputeExecutor executor;

    @BeforeAll
    static void 初始化显式聚合重算数据库() {
        dataSource = new DriverManagerDataSource(
                TIMESCALE.getJdbcUrl(), TIMESCALE.getUsername(), TIMESCALE.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new AggregationExecutionRepository(dataSource);
        executor = new AggregationRecomputeExecutor(repository, "telemetry-recompute-it");
        insertPolicy();
    }

    @Test
    @Order(1)
    void 应纳入超修正窗口样本并在新请求中追加更高版本() {
        insertRaw(uuid(831), 201, FROM.plusSeconds(10), FROM.plusSeconds(1_200), 100D);
        insertRaw(uuid(832), 202, FROM.plusSeconds(20), FROM.plusSeconds(30), 300D);
        setWatermark(202);
        AggregationRecomputeExecutor.Request firstRequest = request(uuid(841), uuid(842));

        AggregationRecomputeExecutor.RunResult first = executor.run(firstRequest);
        AggregationRecomputeExecutor.RunResult replay = executor.run(firstRequest);

        assertThat(first.status()).isEqualTo(AggregationRecomputeExecutor.RunStatus.SUCCEEDED);
        assertThat(first.bucketCount()).isOne();
        assertThat(replay).isEqualTo(first);
        assertThat(jdbcTemplate.queryForList("""
                SELECT average_value, sample_count, latest_received_at,
                       source_watermark, execution_id
                  FROM telemetry_numeric_rollup
                 WHERE device_id = ?
                   AND property_id = 'engine.rpm'
                   AND bucket_start = ?
                 ORDER BY rollup_revision
                """, DEVICE_ID, Timestamp.from(FROM)))
                .containsExactly(Map.of(
                        "average_value", 200D,
                        "sample_count", 2L,
                        "latest_received_at", Timestamp.from(FROM.plusSeconds(1_200)),
                        "source_watermark", 202L,
                        "execution_id", firstRequest.executionId()
                ));

        insertRaw(uuid(833), 203, FROM.plusSeconds(40), FROM.plusSeconds(1_500), 500D);
        setWatermark(203);
        AggregationRecomputeExecutor.Request correctedRequest = request(uuid(843), uuid(844));

        AggregationRecomputeExecutor.RunResult corrected = executor.run(correctedRequest);

        assertThat(corrected.status())
                .isEqualTo(AggregationRecomputeExecutor.RunStatus.SUCCEEDED);
        assertThat(jdbcTemplate.queryForList("""
                SELECT average_value, sample_count, source_watermark, execution_id
                  FROM telemetry_numeric_rollup
                 WHERE device_id = ?
                   AND property_id = 'engine.rpm'
                   AND bucket_start = ?
                 ORDER BY rollup_revision
                """, DEVICE_ID, Timestamp.from(FROM)))
                .containsExactly(
                        Map.of(
                                "average_value", 200D,
                                "sample_count", 2L,
                                "source_watermark", 202L,
                                "execution_id", firstRequest.executionId()),
                        Map.of(
                                "average_value", 300D,
                                "sample_count", 3L,
                                "source_watermark", 203L,
                                "execution_id", correctedRequest.executionId())
                );
    }

    @Test
    @Order(2)
    void 恢复运行中执行时策略证据变化应失败关闭() {
        AggregationRecomputeExecutor.Request request = request(uuid(851), uuid(852));
        AggregationRecomputeExecutor.Preparation preparation = repository.withLock(session ->
                session.prepareRecomputation("telemetry-recompute-crashed", request)
        ).orElseThrow();
        assertThat(preparation.status())
                .isEqualTo(AggregationRecomputeExecutor.PreparationStatus.READY);
        jdbcTemplate.update("""
                UPDATE telemetry_aggregation_policy
                   SET runtime_revision = 8,
                       snapshot_id = ?,
                       content_hash = ?,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE device_id = ?
                """, UPDATED_SNAPSHOT_ID, UPDATED_CONTENT_HASH, DEVICE_ID);

        AggregationRecomputeExecutor.RunResult result = executor.run(request);

        assertThat(result.status()).isEqualTo(AggregationRecomputeExecutor.RunStatus.FAILED);
        assertThat(result.errorSummary()).contains("策略镜像已变化");
        assertThat(jdbcTemplate.queryForMap("""
                SELECT execution_kind, recompute_request_id, target_device_id,
                       target_property_id, target_bucket_seconds, requested_from,
                       requested_to, status, runtime_revision, snapshot_id,
                       policy_content_hash
                  FROM telemetry_aggregation_execution
                 WHERE execution_id = ?
                """, request.executionId()))
                .containsEntry("execution_kind", "RECOMPUTE")
                .containsEntry("recompute_request_id", request.requestId())
                .containsEntry("target_device_id", DEVICE_ID)
                .containsEntry("target_property_id", "engine.rpm")
                .containsEntry("target_bucket_seconds", 300)
                .containsEntry("requested_from", Timestamp.from(FROM))
                .containsEntry("requested_to", Timestamp.from(TO))
                .containsEntry("status", "FAILED")
                .containsEntry("runtime_revision", 7L)
                .containsEntry("snapshot_id", SNAPSHOT_ID)
                .containsEntry("policy_content_hash", CONTENT_HASH);
    }

    private static AggregationRecomputeExecutor.Request request(UUID requestId, UUID executionId) {
        return new AggregationRecomputeExecutor.Request(
                requestId, executionId, DEVICE_ID, "engine.rpm",
                Duration.ofMinutes(5), FROM, TO);
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
                  "correctionWindowSeconds":600,
                  "rules":[{"propertyId":"engine.rpm","bucketSeconds":300}]
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
