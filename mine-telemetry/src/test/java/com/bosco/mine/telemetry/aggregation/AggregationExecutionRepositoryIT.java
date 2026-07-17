package com.bosco.mine.telemetry.aggregation;

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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 TimescaleDB 校验版本化聚合事实、执行证据、幂等边界和数据库保护。
 */
@Testcontainers
class AggregationExecutionRepositoryIT {

    private static final DockerImageName TIMESCALE_IMAGE = DockerImageName
            .parse("timescale/timescaledb:2.28.2-pg18-oss@sha256:"
                    + "8ed8d7336f00a3a90bb6fe709d506172012c7e673e0cf71465491d06e2db5fbd")
            .asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer TIMESCALE = new PostgreSQLContainer(TIMESCALE_IMAGE)
            .withDatabaseName("mine_telemetry")
            .withUsername("mine_ts")
            .withPassword("mine-timescale-test-password");

    private static final UUID DEVICE_ID = uuid(701);
    private static final UUID SNAPSHOT_ID = uuid(702);
    private static final UUID FIRST_EXECUTION_ID = uuid(703);
    private static final UUID SECOND_EXECUTION_ID = uuid(704);
    private static final UUID PROTECTED_EXECUTION_ID = uuid(705);
    private static final UUID INVALID_EXECUTION_ID = uuid(706);
    private static final String CONTENT_HASH = "sha256:" + "c".repeat(64);
    private static final Instant BUCKET_START = Instant.parse("2026-07-17T01:00:00Z");
    private static DataSource dataSource;
    private static JdbcTemplate jdbcTemplate;
    private static AggregationExecutionRepository repository;

    @BeforeAll
    static void 初始化聚合数据库() {
        dataSource = new DriverManagerDataSource(
                TIMESCALE.getJdbcUrl(), TIMESCALE.getUsername(), TIMESCALE.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new AggregationExecutionRepository(dataSource);
    }

    @Test
    void 应使同轮重试幂等并保留不同源水位的聚合版本() {
        AggregationExecutionRepository.RollupWrite first = rollup(42, 1300D);
        AggregationExecutionRepository.RollupWrite corrected = rollup(43, 1320D);

        repository.withLock(session -> {
            session.startExecution(FIRST_EXECUTION_ID, "aggregation-it-a",
                    new AggregationExecutor.Plan(42, 0, 1, 1));
            assertThat(session.writeRollupBatch(FIRST_EXECUTION_ID, List.of(first))).isOne();
            assertThat(session.writeRollupBatch(FIRST_EXECUTION_ID, List.of(first))).isZero();
            session.completeExecution(FIRST_EXECUTION_ID,
                    AggregationExecutor.RunStatus.SUCCEEDED, 0, null);
            return true;
        });
        repository.withLock(session -> {
            session.startExecution(SECOND_EXECUTION_ID, "aggregation-it-b",
                    new AggregationExecutor.Plan(43, 42, 1, 1));
            assertThat(session.writeRollupBatch(SECOND_EXECUTION_ID, List.of(corrected))).isOne();
            session.completeExecution(SECOND_EXECUTION_ID,
                    AggregationExecutor.RunStatus.SUCCEEDED, 0, null);
            return true;
        });

        assertThat(jdbcTemplate.queryForList("""
                SELECT average_value, source_watermark, runtime_revision,
                       snapshot_id, policy_content_hash, execution_id
                  FROM telemetry_numeric_rollup
                 WHERE device_id = ?
                   AND property_id = 'engine.rpm'
                   AND bucket_seconds = 300
                   AND bucket_start = ?
                   AND execution_id IN (?, ?)
                 ORDER BY rollup_revision
                """, DEVICE_ID, Timestamp.from(BUCKET_START),
                FIRST_EXECUTION_ID, SECOND_EXECUTION_ID))
                .containsExactly(
                        Map.of(
                                "average_value", 1300D,
                                "source_watermark", 42L,
                                "runtime_revision", 7L,
                                "snapshot_id", SNAPSHOT_ID,
                                "policy_content_hash", CONTENT_HASH,
                                "execution_id", FIRST_EXECUTION_ID
                        ),
                        Map.of(
                                "average_value", 1320D,
                                "source_watermark", 43L,
                                "runtime_revision", 7L,
                                "snapshot_id", SNAPSHOT_ID,
                                "policy_content_hash", CONTENT_HASH,
                                "execution_id", SECOND_EXECUTION_ID
                        )
                );
        assertThat(jdbcTemplate.queryForList("""
                SELECT status, source_watermark, bucket_count, rollup_watermark
                  FROM telemetry_aggregation_execution
                 WHERE execution_id IN (?, ?)
                 ORDER BY source_watermark
                """, FIRST_EXECUTION_ID, SECOND_EXECUTION_ID))
                .allSatisfy(row -> {
                    assertThat(row.get("status")).isEqualTo("SUCCEEDED");
                    assertThat(row.get("bucket_count")).isEqualTo(1);
                    assertThat(row.get("rollup_watermark")).isNotNull();
                });
    }

    @Test
    void 应拒绝篡改聚合版本和终态执行事实() {
        repository.withLock(session -> {
            session.startExecution(PROTECTED_EXECUTION_ID, "aggregation-it-protected",
                    new AggregationExecutor.Plan(44, 43, 1, 1));
            session.writeRollupBatch(PROTECTED_EXECUTION_ID, List.of(rollup(44, 1330D)));
            session.completeExecution(PROTECTED_EXECUTION_ID,
                    AggregationExecutor.RunStatus.SUCCEEDED, 0, null);
            return true;
        });

        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE telemetry_numeric_rollup
                   SET average_value = average_value + 1
                 WHERE execution_id = ?
                """, PROTECTED_EXECUTION_ID))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("数值聚合事实禁止修改");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                DELETE FROM telemetry_aggregation_execution
                 WHERE execution_id = ?
                """, PROTECTED_EXECUTION_ID))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("聚合执行事实禁止删除");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE telemetry_aggregation_execution
                   SET bucket_count = bucket_count + 1
                 WHERE execution_id = ?
                """, PROTECTED_EXECUTION_ID))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("聚合终态事实禁止修改");
    }

    @Test
    void 应使用独立会话锁防止并发执行() throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement lock = connection.prepareStatement(
                     "SELECT pg_advisory_lock(hashtextextended('mine:telemetry:aggregation', 0))");
             PreparedStatement unlock = connection.prepareStatement(
                     "SELECT pg_advisory_unlock(hashtextextended('mine:telemetry:aggregation', 0))")) {
            lock.execute();

            assertThat(repository.withLock(session -> true)).isEmpty();

            unlock.execute();
        }
    }

    @Test
    void 应在应用和数据库两层拒绝带小数秒的非对齐桶() {
        assertThatThrownBy(() -> new AggregationExecutionRepository.RollupWrite(
                DEVICE_ID,
                "haul-truck",
                "engine.rpm",
                300,
                BUCKET_START.plusMillis(1),
                BUCKET_START.plusSeconds(300).plusMillis(1),
                1300D,
                1200D,
                1400D,
                3,
                (short) 1,
                BUCKET_START.plusSeconds(270),
                45,
                7,
                SNAPSHOT_ID,
                CONTENT_HASH
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UTC 对齐");

        jdbcTemplate.update("""
                INSERT INTO telemetry_aggregation_execution (
                    execution_id, instance_id, status, policy_count,
                    rule_count, source_watermark
                ) VALUES (?, 'aggregation-it-invalid', 'RUNNING', 1, 1, 45)
                """, INVALID_EXECUTION_ID);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO telemetry_numeric_rollup (
                    device_id, product_key, property_id, bucket_seconds,
                    bucket_start, bucket_end, average_value, minimum_value,
                    maximum_value, sample_count, worst_quality, latest_received_at,
                    source_watermark, runtime_revision, snapshot_id,
                    policy_content_hash, execution_id
                ) VALUES (
                    ?, 'haul-truck', 'engine.rpm', 300,
                    ?::TIMESTAMPTZ + INTERVAL '1 millisecond',
                    ?::TIMESTAMPTZ + INTERVAL '300 seconds 1 millisecond',
                    1300, 1200, 1400, 3, 1, ?::TIMESTAMPTZ,
                    45, 7, ?, ?, ?
                )
                """, DEVICE_ID, BUCKET_START.toString(), BUCKET_START.toString(),
                BUCKET_START.toString(), SNAPSHOT_ID, CONTENT_HASH, INVALID_EXECUTION_ID))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_telemetry_numeric_rollup_bucket");
    }

    @Test
    void 新增聚合表和全部字段应具有中文注释与查询索引() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT COALESCE(obj_description(table_name::regclass, 'pg_class'), '') AS comment
                  FROM unnest(ARRAY[
                    'telemetry_numeric_rollup', 'telemetry_aggregation_execution'
                  ]) AS table_name
                 ORDER BY table_name
                """, String.class))
                .hasSize(2)
                .allSatisfy(comment -> assertThat(comment).containsPattern("[\\u4e00-\\u9fff]"));
        assertThat(jdbcTemplate.queryForList("""
                SELECT COALESCE(col_description(table_class.oid, attribute.attnum), '') AS comment
                  FROM pg_class table_class
                  JOIN pg_namespace namespace ON namespace.oid = table_class.relnamespace
                  JOIN pg_attribute attribute ON attribute.attrelid = table_class.oid
                 WHERE namespace.nspname = 'public'
                   AND table_class.relname IN (
                     'telemetry_numeric_rollup', 'telemetry_aggregation_execution'
                   )
                   AND attribute.attnum > 0
                   AND NOT attribute.attisdropped
                 ORDER BY table_class.relname, attribute.attnum
                """, String.class))
                .hasSize(41)
                .allSatisfy(comment -> assertThat(comment).containsPattern("[\\u4e00-\\u9fff]"));
        assertThat(jdbcTemplate.queryForList("""
                SELECT indexname
                  FROM pg_indexes
                 WHERE schemaname = 'public'
                   AND tablename = 'telemetry_numeric_rollup'
                 ORDER BY indexname
                """, String.class))
                .contains(
                        "uq_telemetry_numeric_rollup_execution_bucket",
                        "idx_telemetry_numeric_rollup_query",
                        "idx_telemetry_numeric_rollup_coverage"
                );
    }

    private static AggregationExecutionRepository.RollupWrite rollup(
            long sourceWatermark,
            double average
    ) {
        return new AggregationExecutionRepository.RollupWrite(
                DEVICE_ID,
                "haul-truck",
                "engine.rpm",
                300,
                BUCKET_START,
                BUCKET_START.plusSeconds(300),
                average,
                1200D,
                1400D,
                3,
                (short) 1,
                BUCKET_START.plusSeconds(270),
                sourceWatermark,
                7,
                SNAPSHOT_ID,
                CONTENT_HASH
        );
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("019f6f00-0000-7000-8000-%012d".formatted(suffix));
    }
}
