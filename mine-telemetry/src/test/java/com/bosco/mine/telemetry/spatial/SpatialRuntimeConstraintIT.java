package com.bosco.mine.telemetry.spatial;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 TimescaleDB 验证空间事件运行存储的约束与中文元数据。
 */
@Testcontainers
class SpatialRuntimeConstraintIT {

    private static final DockerImageName TIMESCALE_IMAGE = DockerImageName
            .parse("timescale/timescaledb:2.28.2-pg18-oss@sha256:"
                    + "8ed8d7336f00a3a90bb6fe709d506172012c7e673e0cf71465491d06e2db5fbd")
            .asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer TIMESCALE = new PostgreSQLContainer(TIMESCALE_IMAGE)
            .withDatabaseName("mine_telemetry")
            .withUsername("mine_ts")
            .withPassword("mine-timescale-test-password");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(TIMESCALE.getJdbcUrl(), TIMESCALE.getUsername(), TIMESCALE.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                TIMESCALE.getJdbcUrl(), TIMESCALE.getUsername(), TIMESCALE.getPassword()));
    }

    @Test
    void 应创建六张运行表并补齐全部中文注释() {
        List<String> tables = List.of(
                "spatial_evaluation_queue",
                "spatial_subject_watermark",
                "spatial_zone_state",
                "spatial_event",
                "spatial_event_outbox",
                "spatial_replay_request");

        assertThat(jdbc.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name IN (
                    'spatial_evaluation_queue', 'spatial_subject_watermark',
                    'spatial_zone_state', 'spatial_event',
                    'spatial_event_outbox', 'spatial_replay_request'
                )
                ORDER BY table_name
                """, String.class))
                .containsExactlyElementsOf(tables.stream().sorted().toList());
        assertThat(jdbc.queryForList("""
                SELECT COALESCE(obj_description(table_name::regclass, 'pg_class'), '')
                FROM unnest(ARRAY[
                    'spatial_evaluation_queue', 'spatial_subject_watermark',
                    'spatial_zone_state', 'spatial_event',
                    'spatial_event_outbox', 'spatial_replay_request'
                ]) AS table_name
                ORDER BY table_name
                """, String.class))
                .hasSize(tables.size())
                .allSatisfy(comment -> assertThat(comment).containsPattern("[\\u4e00-\\u9fff]"));
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM pg_class table_class
                JOIN pg_namespace namespace ON namespace.oid = table_class.relnamespace
                JOIN pg_attribute attribute ON attribute.attrelid = table_class.oid
                WHERE namespace.nspname = 'public'
                  AND table_class.relname IN (
                    'spatial_evaluation_queue', 'spatial_subject_watermark',
                    'spatial_zone_state', 'spatial_event',
                    'spatial_event_outbox', 'spatial_replay_request'
                  )
                  AND attribute.attnum > 0
                  AND NOT attribute.attisdropped
                  AND COALESCE(col_description(table_class.oid, attribute.attnum), '')
                      !~ '[一-龥]'
                """, Long.class)).isZero();
    }

    @Test
    void 应固化租约枚举事件幂等和状态外键() {
        assertThat(constraints("spatial_evaluation_queue"))
                .anySatisfy(value -> assertThat(value).contains(
                        "PENDING", "PROCESSING", "PROCESSED", "SKIPPED", "FAILED", "DEAD"))
                .anySatisfy(value -> assertThat(value).contains(
                        "status", "lease_token", "locked_by", "locked_at", "lease_expires_at"))
                .anySatisfy(value -> assertThat(value).contains("FOREIGN KEY", "location_inbox"));
        assertThat(constraints("spatial_zone_state"))
                .anySatisfy(value -> assertThat(value).contains("FOREIGN KEY", "spatial_evaluation_queue"))
                .anySatisfy(value -> assertThat(value).contains("UNKNOWN", "OUTSIDE", "INSIDE"));
        assertThat(constraints("spatial_event"))
                .anySatisfy(value -> assertThat(value).contains(
                        "UNIQUE", "zone_type", "zone_version_id", "tag_id", "event_type",
                        "anchor_location_event_id"))
                .anySatisfy(value -> assertThat(value).contains("ENTER", "EXIT", "DWELL"))
                .anySatisfy(value -> assertThat(value).contains("pg_column_size", "evidence"));
        assertThat(constraints("spatial_event_outbox"))
                .anySatisfy(value -> assertThat(value).contains(
                        "realtime.spatial.event.created", "schema_version"))
                .anySatisfy(value -> assertThat(value).contains(
                        "PENDING", "PUBLISHING", "PUBLISHED", "FAILED", "DEAD"));
    }

    @Test
    void 应拒绝非法租约和超限重放窗口() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO spatial_evaluation_queue (
                    location_event_id, organization_id, site_id, tag_id, occurred_at,
                    ingest_sequence, runtime_revision, status
                ) VALUES (
                    '019f7b51-9b63-70b8-b0e2-581e4f810101',
                    '019f7b51-9b63-70b8-b0e2-581e4f810102',
                    '019f7b51-9b63-70b8-b0e2-581e4f810103',
                    '019f7b51-9b63-70b8-b0e2-581e4f810104',
                    '2026-07-19T00:00:00Z', 1, 1, 'PROCESSING'
                )
                """))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO spatial_replay_request (
                    request_id, organization_id, site_id, zone_type, zone_version_id,
                    requested_by, idempotency_key, reason, window_start, window_end
                ) VALUES (
                    '019f7b51-9b63-70b8-b0e2-581e4f810201',
                    '019f7b51-9b63-70b8-b0e2-581e4f810202',
                    '019f7b51-9b63-70b8-b0e2-581e4f810203', 'GEOFENCE',
                    '019f7b51-9b63-70b8-b0e2-581e4f810204',
                    '019f7b51-9b63-70b8-b0e2-581e4f810205', 'replay-too-long',
                    '超过允许窗口', '2026-01-01T00:00:00Z', '2026-02-02T00:00:01Z'
                )
                """))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * 查询指定表的规范化约束定义。
     *
     * @param tableName 表名
     * @return 约束定义列表
     */
    private List<String> constraints(String tableName) {
        return jdbc.queryForList("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conrelid = (?::regclass)
                ORDER BY conname
                """, String.class, tableName);
    }
}
