package com.bosco.mine.telemetry.storage;

import com.bosco.mine.model.message.UnifiedDeviceMessage.MessageKind;
import com.bosco.mine.model.message.UnifiedDeviceMessage.Quality;
import com.bosco.mine.telemetry.pipeline.TelemetrySample;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用 Apache-2.0 OSS 镜像验证遥测时序库迁移和重复启动行为。
 */
@Testcontainers
class TelemetryMigrationIT {

    private static final DockerImageName TIMESCALE_IMAGE = DockerImageName
            .parse("timescale/timescaledb:2.28.2-pg18-oss@sha256:"
                    + "8ed8d7336f00a3a90bb6fe709d506172012c7e673e0cf71465491d06e2db5fbd")
            .asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer TIMESCALE = new PostgreSQLContainer(TIMESCALE_IMAGE)
            .withDatabaseName("mine_telemetry")
            .withUsername("mine_ts")
            .withPassword("mine-timescale-test-password");

    @Test
    void 存量库升级应回放最新状态创建水位并拒绝退役启动周期() throws Exception {
        execute("DROP EXTENSION IF EXISTS timescaledb CASCADE");
        Flyway baselineFlyway = Flyway.configure()
                .dataSource(TIMESCALE.getJdbcUrl(), TIMESCALE.getUsername(), TIMESCALE.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("5"))
                .load();
        Flyway flyway = Flyway.configure()
                .dataSource(TIMESCALE.getJdbcUrl(), TIMESCALE.getUsername(), TIMESCALE.getPassword())
                .locations("classpath:db/migration")
                .load();

        MigrateResult baseline = baselineFlyway.migrate();
        insertLegacyTelemetry();
        String legacyLocationTableOid = queryStrings(
                "SELECT 'location_point'::regclass::oid::TEXT").getFirst();
        MigrateResult upgraded = flyway.migrate();

        assertThat(baseline.migrationsExecuted).isPositive();
        assertThat(upgraded.migrationsExecuted).isPositive();
        assertThat(queryStrings("SELECT extname FROM pg_extension WHERE extname = 'timescaledb'"))
                .containsExactly("timescaledb");
        assertThat(queryStrings("SELECT extversion FROM pg_extension WHERE extname = 'timescaledb'"))
                .containsExactly("2.28.2");
        assertThat(queryStrings("SHOW timescaledb.license")).containsExactly("apache");
        assertThat(queryStrings("""
                SELECT hypertable_name
                FROM timescaledb_information.hypertables
                WHERE hypertable_schema = 'public'
                ORDER BY hypertable_name
                """))
                .containsExactly(
                        "location_point", "telemetry_numeric", "telemetry_numeric_rollup");
        assertThat(queryStrings("""
                SELECT tablename || ':' || indexname
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename IN (
                      'telemetry_numeric', 'location_point', 'telemetry_inbox',
                      'telemetry_latest_state', 'telemetry_retired_boot',
                      'telemetry_latest_numeric', 'telemetry_ingestion_watermark'
                  )
                ORDER BY tablename, indexname
                """))
                .contains(
                        "location_point:idx_location_device_time",
                        "location_point:idx_location_tag_time",
                        "location_point:uq_location_event_time",
                        "telemetry_inbox:idx_ti_processed_at",
                        "telemetry_inbox:idx_ti_received_at",
                        "telemetry_inbox:pk_telemetry_inbox",
                        "telemetry_inbox:uq_ti_business_key",
                        "telemetry_numeric:idx_tn_device_property_occurred_at",
                        "telemetry_numeric:idx_tn_device_property_time",
                        "telemetry_numeric:idx_tn_product_property_time",
                        "telemetry_numeric:uq_tn_event_property_time"
                );
        assertThat(queryStrings("""
                SELECT COALESCE(obj_description(table_name::regclass, 'pg_class'), '')
                FROM unnest(ARRAY[
                    'telemetry_numeric', 'location_point', 'telemetry_inbox',
                    'telemetry_latest_state', 'telemetry_retired_boot',
                    'telemetry_latest_numeric', 'telemetry_ingestion_watermark'
                ]) AS table_name
                ORDER BY table_name
                """))
                .hasSize(7)
                .allSatisfy(comment -> assertThat(comment).containsPattern("[\\u4e00-\\u9fff]"));
        assertThat(queryStrings("""
                SELECT COALESCE(col_description(table_class.oid, attribute.attnum), '')
                FROM pg_class table_class
                JOIN pg_namespace namespace ON namespace.oid = table_class.relnamespace
                JOIN pg_attribute attribute ON attribute.attrelid = table_class.oid
                WHERE namespace.nspname = 'public'
                  AND table_class.relname IN (
                      'telemetry_numeric', 'location_point', 'telemetry_inbox',
                      'telemetry_latest_state', 'telemetry_retired_boot',
                      'telemetry_latest_numeric', 'telemetry_ingestion_watermark'
                  )
                  AND attribute.attnum > 0
                  AND NOT attribute.attisdropped
                ORDER BY table_class.relname, attribute.attnum
                """))
                .hasSize(73)
                .allSatisfy(comment -> assertThat(comment).containsPattern("[\\u4e00-\\u9fff]"));
        assertThat(queryStrings("""
                SELECT COALESCE(obj_description(table_name::regclass, 'pg_class'), '')
                FROM unnest(ARRAY[
                    'telemetry_strategy_snapshot_stage',
                    'telemetry_strategy_runtime_revision',
                    'telemetry_data_quality_policy',
                    'telemetry_alarm_policy',
                    'telemetry_retention_policy',
                    'telemetry_aggregation_policy',
                    'telemetry_aggregation_execution',
                    'telemetry_numeric_rollup',
                    'telemetry_retention_execution',
                    'telemetry_strategy_event_inbox',
                    'telemetry_quality_evidence',
                    'telemetry_alarm_runtime_state',
                    'telemetry_outbox_event'
                ]) AS table_name
                ORDER BY table_name
                """))
                .hasSize(13)
                .allSatisfy(comment -> assertThat(comment).containsPattern("[\\u4e00-\\u9fff]"));
        assertThat(queryStrings("""
                SELECT COALESCE(col_description(table_class.oid, attribute.attnum), '')
                FROM pg_class table_class
                JOIN pg_namespace namespace ON namespace.oid = table_class.relnamespace
                JOIN pg_attribute attribute ON attribute.attrelid = table_class.oid
                WHERE namespace.nspname = 'public'
                  AND table_class.relname IN (
                    'telemetry_strategy_snapshot_stage',
                    'telemetry_strategy_runtime_revision',
                      'telemetry_data_quality_policy',
                      'telemetry_alarm_policy',
                      'telemetry_retention_policy',
                      'telemetry_aggregation_policy',
                      'telemetry_aggregation_execution',
                      'telemetry_numeric_rollup',
                      'telemetry_retention_execution',
                      'telemetry_strategy_event_inbox',
                    'telemetry_quality_evidence',
                    'telemetry_alarm_runtime_state',
                    'telemetry_outbox_event'
                  )
                  AND attribute.attnum > 0
                  AND NOT attribute.attisdropped
                ORDER BY table_class.relname, attribute.attnum
                """))
                .hasSize(150)
                .allSatisfy(comment -> assertThat(comment).containsPattern("[\\u4e00-\\u9fff]"));
        assertThat(queryStrings("""
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = 'telemetry_outbox_event'
                ORDER BY indexname
                """)).contains("idx_telemetry_outbox_event_unfinished_status");
        assertThat(queryStrings("""
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = 'telemetry_numeric'
                ORDER BY indexname
                """)).contains(
                        "idx_tn_retention_device_time",
                        "idx_tn_aggregation_ingest_time"
                );

        assertThat(queryStrings("SELECT 'location_point'::regclass::oid::TEXT"))
                .containsExactly(legacyLocationTableOid);
        assertThat(queryStrings("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'location_point'
                """))
                .containsExactlyInAnyOrder(
                        "time", "organization_id", "site_id", "source_device_id", "tag_id",
                        "source_coordinate_system_id", "source_x", "source_y", "source_z",
                        "target_coordinate_system_id", "local_x", "local_y", "local_z",
                        "transform_version_id", "binding_type", "binding_id", "vehicle_id",
                        "person_id", "speed_meters_per_second", "heading_degrees",
                        "accuracy_meters", "quality_status", "quality_reason", "boot_id",
                        "sequence", "event_id", "ingest_sequence", "received_at");
        assertThat(queryStrings("""
                SELECT local_x::TEXT || ':' || local_y::TEXT || ':' || local_z::TEXT
                       || ':' || quality_status || ':' || (source_x IS NULL)::TEXT
                FROM location_point
                WHERE event_id = '019f63e2-071f-706a-bd12-4dad7150e772'
                """))
                .containsExactly("10:20:3:GOOD:true");
        assertThat(queryStrings("""
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = 'location_point'
                ORDER BY indexname
                """))
                .contains(
                        "uq_location_event_time",
                        "uq_location_source_cycle_time",
                        "idx_location_site_time",
                        "idx_location_site_ingest",
                        "idx_location_site_tag_time",
                        "idx_location_site_vehicle_time",
                        "idx_location_site_person_time");
        assertThat(queryStrings("""
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = 'location_realtime_outbox'
                ORDER BY indexname
                """))
                .contains("idx_location_outbox_site_tag_ingest");
        assertThat(queryStrings("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN (
                      'location_current_rebuild_watermark',
                      'location_snapshot_stage',
                      'location_runtime_revision',
                      'location_inbox',
                      'location_quarantine',
                      'location_realtime_outbox'
                  )
                ORDER BY table_name
                """))
                .containsExactly(
                        "location_current_rebuild_watermark",
                        "location_inbox",
                        "location_quarantine",
                        "location_realtime_outbox",
                        "location_runtime_revision",
                        "location_snapshot_stage");
        assertThat(queryStrings("""
                SELECT COALESCE(obj_description(table_name::regclass, 'pg_class'), '')
                FROM unnest(ARRAY[
                    'location_point',
                    'location_current_rebuild_watermark',
                    'location_snapshot_stage',
                    'location_runtime_revision',
                    'location_inbox',
                    'location_quarantine',
                    'location_realtime_outbox'
                ]) AS table_name
                ORDER BY table_name
                """))
                .hasSize(7)
                .allSatisfy(comment -> assertThat(comment).containsPattern("[\\u4e00-\\u9fff]"));
        assertThat(queryStrings("""
                SELECT COALESCE(col_description(table_class.oid, attribute.attnum), '')
                FROM pg_class table_class
                JOIN pg_namespace namespace ON namespace.oid = table_class.relnamespace
                JOIN pg_attribute attribute ON attribute.attrelid = table_class.oid
                WHERE namespace.nspname = 'public'
                  AND table_class.relname IN (
                      'location_point',
                      'location_current_rebuild_watermark',
                      'location_snapshot_stage',
                      'location_runtime_revision',
                      'location_inbox',
                      'location_quarantine',
                      'location_realtime_outbox'
                  )
                  AND attribute.attnum > 0
                  AND NOT attribute.attisdropped
                ORDER BY table_class.relname, attribute.attnum
                """))
                .hasSizeGreaterThan(28)
                .allSatisfy(comment -> assertThat(comment).containsPattern("[\\u4e00-\\u9fff]"));
        assertThat(queryStrings("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conrelid = 'location_quarantine'::regclass
                ORDER BY conname
                """))
                .anySatisfy(definition -> assertThat(definition)
                        .contains("pg_column_size", "expires_at", "created_at"));
        assertThat(queryStrings("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conrelid = 'location_snapshot_stage'::regclass
                  AND conname = 'ck_location_snapshot_stage_type'
                """))
                .singleElement()
                .satisfies(definition -> assertThat(definition)
                        .contains("'SOURCE_DEVICE'::character varying"));

        assertThat(queryStrings("""
                SELECT current_boot_id || ':' || highest_sequence
                FROM telemetry_latest_state
                WHERE device_id = '019f63e2-071f-706a-bd12-4dad7150e75d'
                """)).containsExactly("boot-b:0");
        assertThat(queryStrings("""
                SELECT to_char(first_seen_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS')
                       || ':' ||
                       to_char(last_received_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS')
                FROM telemetry_latest_state
                WHERE device_id = '019f63e2-071f-706a-bd12-4dad7150e75d'
                """)).containsExactly("2026-07-15 03:00:01:2026-07-15 03:01:01");
        assertThat(queryStrings("""
                SELECT boot_id
                FROM telemetry_retired_boot
                WHERE device_id = '019f63e2-071f-706a-bd12-4dad7150e75d'
                """)).containsExactly("boot-a");
        assertThat(queryStrings("""
                SELECT value_double::TEXT
                FROM telemetry_latest_numeric
                WHERE device_id = '019f63e2-071f-706a-bd12-4dad7150e75d'
                  AND property_id = 'engine.rpm'
                """)).containsExactly("1400");
        assertThat(queryStrings("""
                SELECT COUNT(DISTINCT ingest_id)::TEXT FROM telemetry_numeric
                """)).containsExactly("3");

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                TIMESCALE.getJdbcUrl(), TIMESCALE.getUsername(), TIMESCALE.getPassword()
        );
        TelemetryBatchWriter writer = new TelemetryBatchWriter(dataSource);
        TelemetrySample lateRetiredBoot = new TelemetrySample(
                UUID.fromString("019f63e2-071f-706a-bd12-4dad7150e764"),
                UUID.fromString("019f63e2-071f-706a-bd12-4dad7150e75d"),
                "haul-truck",
                "engine.rpm",
                1800D,
                Quality.GOOD,
                "boot-a",
                10L,
                MessageKind.TELEMETRY,
                Instant.parse("2026-07-15T03:02:00Z"),
                Instant.parse("2026-07-15T03:02:01Z"),
                Instant.parse("2026-07-15T03:02:00Z")
        );

        assertThat(writer.write(List.of(lateRetiredBoot)).latestEventIds()).isEmpty();
        assertThat(queryStrings("""
                SELECT value_double::TEXT
                FROM telemetry_latest_numeric
                WHERE device_id = '019f63e2-071f-706a-bd12-4dad7150e75d'
                  AND property_id = 'engine.rpm'
                """)).containsExactly("1400");
        assertThat(queryStrings("SELECT COUNT(*)::TEXT FROM telemetry_numeric"))
                .containsExactly("4");

        MigrateResult repeated = flyway.migrate();

        assertThat(repeated.migrationsExecuted).isZero();
        assertThat(queryStrings("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN (
                      'telemetry_numeric', 'location_point', 'telemetry_inbox',
                      'telemetry_latest_state', 'telemetry_retired_boot',
                      'telemetry_latest_numeric', 'telemetry_ingestion_watermark'
                  )
                ORDER BY table_name
                """))
                .containsExactly(
                        "location_point",
                        "telemetry_inbox",
                        "telemetry_ingestion_watermark",
                        "telemetry_latest_numeric",
                        "telemetry_latest_state",
                        "telemetry_numeric",
                        "telemetry_retired_boot"
                );
        assertThat(queryStrings("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN (
                    'telemetry_strategy_snapshot_stage',
                    'telemetry_strategy_runtime_revision',
                    'telemetry_data_quality_policy',
                    'telemetry_alarm_policy',
                    'telemetry_retention_policy',
                    'telemetry_aggregation_policy',
                    'telemetry_aggregation_execution',
                    'telemetry_numeric_rollup',
                    'telemetry_retention_execution',
                    'telemetry_strategy_event_inbox',
                    'telemetry_quality_evidence',
                    'telemetry_alarm_runtime_state',
                    'telemetry_outbox_event'
                  )
                ORDER BY table_name
                """))
                .containsExactly(
                        "telemetry_aggregation_execution",
                        "telemetry_aggregation_policy",
                        "telemetry_alarm_policy",
                        "telemetry_alarm_runtime_state",
                        "telemetry_data_quality_policy",
                        "telemetry_numeric_rollup",
                        "telemetry_outbox_event",
                        "telemetry_quality_evidence",
                        "telemetry_retention_execution",
                        "telemetry_retention_policy",
                        "telemetry_strategy_event_inbox",
                        "telemetry_strategy_runtime_revision",
                        "telemetry_strategy_snapshot_stage"
                );
    }

    private void insertLegacyTelemetry() throws Exception {
        execute("""
                INSERT INTO telemetry_inbox (
                    event_id, device_id, message_type, boot_id, sequence,
                    received_at, processed_at
                ) VALUES
                    ('019f63e2-071f-706a-bd12-4dad7150e761',
                     '019f63e2-071f-706a-bd12-4dad7150e75d', 'TELEMETRY',
                     'boot-a', 7, '2026-07-15T03:00:01Z', '2026-07-15T03:00:02Z'),
                    ('019f63e2-071f-706a-bd12-4dad7150e762',
                     '019f63e2-071f-706a-bd12-4dad7150e75d', 'TELEMETRY',
                     'boot-b', 0, '2026-07-15T03:00:11Z', '2026-07-15T03:00:12Z'),
                    ('019f63e2-071f-706a-bd12-4dad7150e763',
                     '019f63e2-071f-706a-bd12-4dad7150e75d', 'TELEMETRY',
                     'boot-a', 9, '2026-07-15T03:01:01Z', '2026-07-15T03:01:02Z');

                INSERT INTO telemetry_numeric (
                    time, occurred_at, device_id, product_key, property_id, value_double,
                    quality, boot_id, sequence, event_id, received_at
                ) VALUES
                    ('2026-07-15T03:00:00Z', '2026-07-15T03:00:00Z',
                     '019f63e2-071f-706a-bd12-4dad7150e75d', 'haul-truck',
                     'engine.rpm', 1280, 0, 'boot-a', 7,
                     '019f63e2-071f-706a-bd12-4dad7150e761', '2026-07-15T03:00:01Z'),
                    ('2026-07-15T03:00:10Z', '2026-07-15T03:00:10Z',
                     '019f63e2-071f-706a-bd12-4dad7150e75d', 'haul-truck',
                     'engine.rpm', 1400, 0, 'boot-b', 0,
                     '019f63e2-071f-706a-bd12-4dad7150e762', '2026-07-15T03:00:11Z'),
                    ('2026-07-15T03:01:00Z', '2026-07-15T03:01:00Z',
                     '019f63e2-071f-706a-bd12-4dad7150e75d', 'haul-truck',
                     'engine.rpm', 1600, 0, 'boot-a', 9,
                     '019f63e2-071f-706a-bd12-4dad7150e763', '2026-07-15T03:01:01Z');

                INSERT INTO location_point (
                    time, device_id, tag_id, x, y, z, speed, heading, quality,
                    coordinate_system_id, boot_id, sequence, event_id, received_at
                ) VALUES (
                    '2026-07-15T03:00:00Z',
                    '019f63e2-071f-706a-bd12-4dad7150e75d',
                    '019f63e2-071f-706a-bd12-4dad7150e770',
                    10, 20, 3, 6.5, 180, 0,
                    '019f63e2-071f-706a-bd12-4dad7150e771',
                    'boot-a', 7,
                    '019f63e2-071f-706a-bd12-4dad7150e772',
                    '2026-07-15T03:00:01Z');
                """);
    }

    /**
     * 执行不返回结果集的数据库准备语句。
     *
     * @param sql 数据库语句
     * @throws Exception 数据库访问失败
     */
    private void execute(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                TIMESCALE.getJdbcUrl(), TIMESCALE.getUsername(), TIMESCALE.getPassword()
        ); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
        }
    }

    /**
     * 执行只返回一个文本列的结构查询。
     *
     * @param sql 查询语句
     * @return 第一列字符串列表
     * @throws Exception 数据库访问失败
     */
    private List<String> queryStrings(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                TIMESCALE.getJdbcUrl(), TIMESCALE.getUsername(), TIMESCALE.getPassword()
        ); PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            java.util.ArrayList<String> values = new java.util.ArrayList<>();
            while (resultSet.next()) values.add(resultSet.getString(1));
            return List.copyOf(values);
        }
    }
}
