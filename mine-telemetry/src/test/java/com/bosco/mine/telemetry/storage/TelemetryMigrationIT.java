package com.bosco.mine.telemetry.storage;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

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
    void 空库迁移应创建OSS扩展遥测表Hypertable和查询索引且可重复启动() throws Exception {
        execute("DROP EXTENSION IF EXISTS timescaledb CASCADE");
        Flyway flyway = Flyway.configure()
                .dataSource(TIMESCALE.getJdbcUrl(), TIMESCALE.getUsername(), TIMESCALE.getPassword())
                .locations("classpath:db/migration")
                .load();

        MigrateResult first = flyway.migrate();

        assertThat(first.migrationsExecuted).isPositive();
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
                .containsExactly("location_point", "telemetry_numeric");
        assertThat(queryStrings("""
                SELECT tablename || ':' || indexname
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename IN ('telemetry_numeric', 'location_point')
                ORDER BY tablename, indexname
                """))
                .contains(
                        "location_point:idx_location_device_time",
                        "location_point:idx_location_tag_time",
                        "location_point:uq_location_event_time",
                        "telemetry_numeric:idx_tn_device_property_time",
                        "telemetry_numeric:idx_tn_product_property_time",
                        "telemetry_numeric:uq_tn_event_property_time"
                );
        assertThat(queryStrings("""
                SELECT COALESCE(obj_description(table_name::regclass, 'pg_class'), '')
                FROM unnest(ARRAY['telemetry_numeric', 'location_point']) AS table_name
                ORDER BY table_name
                """))
                .hasSize(2)
                .allSatisfy(comment -> assertThat(comment).containsPattern("[\\u4e00-\\u9fff]"));
        assertThat(queryStrings("""
                SELECT COALESCE(col_description(table_class.oid, attribute.attnum), '')
                FROM pg_class table_class
                JOIN pg_namespace namespace ON namespace.oid = table_class.relnamespace
                JOIN pg_attribute attribute ON attribute.attrelid = table_class.oid
                WHERE namespace.nspname = 'public'
                  AND table_class.relname IN ('telemetry_numeric', 'location_point')
                  AND attribute.attnum > 0
                  AND NOT attribute.attisdropped
                ORDER BY table_class.relname, attribute.attnum
                """))
                .hasSize(24)
                .allSatisfy(comment -> assertThat(comment).containsPattern("[\\u4e00-\\u9fff]"));

        MigrateResult repeated = flyway.migrate();

        assertThat(repeated.migrationsExecuted).isZero();
        assertThat(queryStrings("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('telemetry_numeric', 'location_point')
                ORDER BY table_name
                """))
                .containsExactly("location_point", "telemetry_numeric");
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
