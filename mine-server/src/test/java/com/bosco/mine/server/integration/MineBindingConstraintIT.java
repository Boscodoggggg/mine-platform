package com.bosco.mine.server.integration;

import com.bosco.mine.server.MineServerApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 PostgreSQL 双事务校验矿区资产时间绑定排斥约束。
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(
        classes = MineServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class MineBindingConstraintIT {

    private static final Instant T0 = Instant.parse("2026-07-18T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-07-18T01:00:00Z");
    private static final Instant T2 = Instant.parse("2026-07-18T02:00:00Z");
    private static final Instant T3 = Instant.parse("2026-07-18T03:00:00Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("mine")
            .withUsername("mine")
            .withPassword("mine-test-password");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID organizationId;
    private UUID siteId;
    private UUID firstVehicleId;
    private UUID secondVehicleId;
    private UUID thirdVehicleId;
    private UUID firstDeviceId;
    private UUID secondDeviceId;
    private UUID thirdDeviceId;
    private UUID firstTagId;
    private UUID secondTagId;
    private UUID firstPersonId;
    private UUID secondPersonId;

    @BeforeEach
    void 准备绑定对象() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    mine_person_vehicle_binding,
                    mine_person_tag_binding,
                    mine_vehicle_tag_binding,
                    mine_location_tag_binding_claim,
                    mine_vehicle_device_binding,
                    mine_driver_qualification,
                    mine_person,
                    mine_location_tag,
                    mine_vehicle,
                    dev_device,
                    dev_product,
                    mine_user_scope_grant,
                    mine_site,
                    mine_organization
                CASCADE
                """);
        organizationId = UUID.randomUUID();
        siteId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_organization (id, organization_code, name)
                VALUES (?, 'binding-group', '绑定测试集团')
                """, organizationId);
        jdbcTemplate.update("""
                INSERT INTO mine_site (id, organization_id, site_code, name, timezone)
                VALUES (?, ?, 'binding-pit', '绑定测试矿区', 'Asia/Shanghai')
                """, siteId, organizationId);
        jdbcTemplate.update("""
                INSERT INTO dev_product (id, product_key, name, device_type, status)
                VALUES (?, 'binding-product', '绑定测试产品', 'LOCATION', 'ACTIVE')
                """, productId);

        firstVehicleId = insertVehicle("binding-vehicle-1");
        secondVehicleId = insertVehicle("binding-vehicle-2");
        thirdVehicleId = insertVehicle("binding-vehicle-3");
        firstDeviceId = insertDevice(productId, "binding-device-1");
        secondDeviceId = insertDevice(productId, "binding-device-2");
        thirdDeviceId = insertDevice(productId, "binding-device-3");
        firstTagId = insertTag("binding-tag-1");
        secondTagId = insertTag("binding-tag-2");
        firstPersonId = insertPerson("binding-person-1");
        secondPersonId = insertPerson("binding-person-2");
    }

    @Test
    void 设备不能重叠绑定两车且车辆不能同时存在两个主设备() throws Exception {
        SQLException deviceConflict = concurrentConflict(
                connection -> insertVehicleDevice(
                        connection, firstVehicleId, firstDeviceId, "PRIMARY", T0, T2),
                connection -> insertVehicleDevice(
                        connection, secondVehicleId, firstDeviceId, "PRIMARY", T1, T3));
        assertThat(deviceConflict.getSQLState()).isEqualTo("23P01");

        inTransaction(connection -> insertVehicleDevice(
                connection, secondVehicleId, firstDeviceId, "PRIMARY", T2, T3));

        SQLException primaryConflict = concurrentConflict(
                connection -> insertVehicleDevice(
                        connection, thirdVehicleId, secondDeviceId, "PRIMARY", T0, T2),
                connection -> insertVehicleDevice(
                        connection, thirdVehicleId, thirdDeviceId, "PRIMARY", T1, T3));
        assertThat(primaryConflict.getSQLState()).isEqualTo("23P01");
    }

    @Test
    void 车辆标签和人员标签必须通过共享占用台账跨表排斥() throws Exception {
        SQLException conflict = concurrentConflict(
                connection -> insertVehicleTag(
                        connection, firstVehicleId, firstTagId, T0, T2),
                connection -> insertPersonTag(
                        connection, firstPersonId, firstTagId, T1, T3));
        assertThat(conflict.getSQLState()).isEqualTo("23P01");

        inTransaction(connection -> insertPersonTag(
                connection, firstPersonId, firstTagId, T2, T3));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mine_location_tag_binding_claim WHERE tag_id = ?",
                Long.class, firstTagId)).isEqualTo(2);
    }

    @Test
    void 标签占用台账必须在提交时与恰好一个真实类型绑定完全一致() {
        assertCommitRejected(connection -> insertClaimOnly(
                connection, UUID.randomUUID(), "VEHICLE", secondTagId, T0, T2));

        assertCommitRejected(connection -> {
            UUID bindingId = UUID.randomUUID();
            insertClaimOnly(connection, bindingId, "VEHICLE", secondTagId, T0, T2);
            insertVehicleTagRow(
                    connection, bindingId, firstVehicleId, secondTagId, T0, T2);
            insertPersonTagRow(
                    connection, bindingId, firstPersonId, secondTagId, T0, T2);
        });
    }

    @Test
    void 车辆和人员各自在同一时刻只能使用一个定位标签() throws Exception {
        SQLException vehicleConflict = concurrentConflict(
                connection -> insertVehicleTag(
                        connection, secondVehicleId, firstTagId, T0, T2),
                connection -> insertVehicleTag(
                        connection, secondVehicleId, secondTagId, T1, T3));
        assertThat(vehicleConflict.getSQLState()).isEqualTo("23P01");

        SQLException personConflict = concurrentConflict(
                connection -> insertPersonTag(
                        connection, firstPersonId, firstTagId, T2, T3),
                connection -> insertPersonTag(
                        connection, firstPersonId, secondTagId, T2, T3));
        assertThat(personConflict.getSQLState()).isEqualTo("23P01");
    }

    @Test
    void 司机不能重叠驾驶两车且车辆不能同时存在两个主司机() throws Exception {
        SQLException driverConflict = concurrentConflict(
                connection -> insertPersonVehicle(
                        connection, firstPersonId, firstVehicleId, "PRIMARY_DRIVER", T0, T2),
                connection -> insertPersonVehicle(
                        connection, firstPersonId, secondVehicleId, "RELIEF_DRIVER", T1, T3));
        assertThat(driverConflict.getSQLState()).isEqualTo("23P01");

        inTransaction(connection -> insertPersonVehicle(
                connection, firstPersonId, secondVehicleId, "RELIEF_DRIVER", T2, T3));

        SQLException primaryDriverConflict = concurrentConflict(
                connection -> insertPersonVehicle(
                        connection, firstPersonId, thirdVehicleId, "PRIMARY_DRIVER", T3, null),
                connection -> insertPersonVehicle(
                        connection, secondPersonId, thirdVehicleId, "PRIMARY_DRIVER", T1, null));
        assertThat(primaryDriverConflict.getSQLState()).isEqualTo("23P01");
    }

    private UUID insertVehicle(String code) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_vehicle (
                    id, organization_id, site_id, vehicle_code, name, vehicle_type
                ) VALUES (?, ?, ?, ?, ?, 'HAUL_TRUCK')
                """, id, organizationId, siteId, code, code);
        return id;
    }

    private UUID insertDevice(UUID productId, String code) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO dev_device (id, product_id, device_code, name)
                VALUES (?, ?, ?, ?)
                """, id, productId, code, code);
        return id;
    }

    private UUID insertTag(String code) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_location_tag (id, organization_id, site_id, tag_code)
                VALUES (?, ?, ?, ?)
                """, id, organizationId, siteId, code);
        return id;
    }

    private UUID insertPerson(String code) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_person (
                    id, organization_id, site_id, person_code, name, person_type,
                    phone_ciphertext, phone_masked, certificate_ciphertext,
                    certificate_masked, employment_from
                ) VALUES (
                    ?, ?, ?, ?, ?, 'DRIVER',
                    'v1:bm9uY2U:Y2lwaGVydGV4dA', '138****8000',
                    'v1:bm9uY2U:Y2lwaGVydGV4dA', '410***********1234', DATE '2025-01-01'
                )
                """, id, organizationId, siteId, code, code);
        return id;
    }

    private void insertVehicleDevice(
            Connection connection,
            UUID vehicleId,
            UUID deviceId,
            String role,
            Instant from,
            Instant to
    ) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO mine_vehicle_device_binding (
                    id, organization_id, site_id, vehicle_id, device_id,
                    binding_role, valid_from, valid_to
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, organizationId);
            statement.setObject(3, siteId);
            statement.setObject(4, vehicleId);
            statement.setObject(5, deviceId);
            statement.setString(6, role);
            setInstant(statement, 7, from);
            setInstant(statement, 8, to);
            statement.executeUpdate();
        }
    }

    private void insertVehicleTag(
            Connection connection, UUID vehicleId, UUID tagId, Instant from, Instant to
    ) throws SQLException {
        UUID bindingId = UUID.randomUUID();
        insertClaimOnly(connection, bindingId, "VEHICLE", tagId, from, to);
        insertVehicleTagRow(connection, bindingId, vehicleId, tagId, from, to);
    }

    private void insertPersonTag(
            Connection connection, UUID personId, UUID tagId, Instant from, Instant to
    ) throws SQLException {
        UUID bindingId = UUID.randomUUID();
        insertClaimOnly(connection, bindingId, "PERSON", tagId, from, to);
        insertPersonTagRow(connection, bindingId, personId, tagId, from, to);
    }

    private void insertClaimOnly(
            Connection connection,
            UUID bindingId,
            String type,
            UUID tagId,
            Instant from,
            Instant to
    ) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO mine_location_tag_binding_claim (
                    binding_id, organization_id, site_id, binding_type,
                    tag_id, valid_from, valid_to
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, bindingId);
            statement.setObject(2, organizationId);
            statement.setObject(3, siteId);
            statement.setString(4, type);
            statement.setObject(5, tagId);
            setInstant(statement, 6, from);
            setInstant(statement, 7, to);
            statement.executeUpdate();
        }
    }

    private void insertVehicleTagRow(
            Connection connection,
            UUID bindingId,
            UUID vehicleId,
            UUID tagId,
            Instant from,
            Instant to
    ) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO mine_vehicle_tag_binding (
                    id, organization_id, site_id, vehicle_id, tag_id, valid_from, valid_to
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, bindingId);
            statement.setObject(2, organizationId);
            statement.setObject(3, siteId);
            statement.setObject(4, vehicleId);
            statement.setObject(5, tagId);
            setInstant(statement, 6, from);
            setInstant(statement, 7, to);
            statement.executeUpdate();
        }
    }

    private void insertPersonTagRow(
            Connection connection,
            UUID bindingId,
            UUID personId,
            UUID tagId,
            Instant from,
            Instant to
    ) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO mine_person_tag_binding (
                    id, organization_id, site_id, person_id, tag_id, valid_from, valid_to
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, bindingId);
            statement.setObject(2, organizationId);
            statement.setObject(3, siteId);
            statement.setObject(4, personId);
            statement.setObject(5, tagId);
            setInstant(statement, 6, from);
            setInstant(statement, 7, to);
            statement.executeUpdate();
        }
    }

    private void insertPersonVehicle(
            Connection connection,
            UUID personId,
            UUID vehicleId,
            String role,
            Instant from,
            Instant to
    ) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO mine_person_vehicle_binding (
                    id, organization_id, site_id, person_id, vehicle_id,
                    assignment_role, valid_from, valid_to
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, organizationId);
            statement.setObject(3, siteId);
            statement.setObject(4, personId);
            statement.setObject(5, vehicleId);
            statement.setString(6, role);
            setInstant(statement, 7, from);
            setInstant(statement, 8, to);
            statement.executeUpdate();
        }
    }

    private SQLException concurrentConflict(SqlOperation first, SqlOperation second) throws Exception {
        try (Connection firstConnection = connection();
             Connection secondConnection = connection();
             var executor = Executors.newSingleThreadExecutor()) {
            firstConnection.setAutoCommit(false);
            secondConnection.setAutoCommit(false);
            first.execute(firstConnection);
            CountDownLatch ready = new CountDownLatch(1);
            Future<SQLException> result = executor.submit(() -> {
                ready.countDown();
                try {
                    second.execute(secondConnection);
                    secondConnection.commit();
                    return null;
                } catch (SQLException exception) {
                    secondConnection.rollback();
                    return exception;
                }
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            firstConnection.commit();
            SQLException exception = result.get(10, TimeUnit.SECONDS);
            assertThat((Throwable) exception).as("重叠绑定必须由数据库拒绝").isNotNull();
            return exception;
        }
    }

    private void inTransaction(SqlOperation operation) throws Exception {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            operation.execute(connection);
            connection.commit();
        }
    }

    private void assertCommitRejected(SqlOperation operation) {
        assertThatThrownBy(() -> {
            try (Connection connection = connection()) {
                connection.setAutoCommit(false);
                operation.execute(connection);
                connection.commit();
            }
        }).isInstanceOf(SQLException.class)
                .extracting(throwable -> ((SQLException) throwable).getSQLState())
                .isEqualTo("23514");
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword());
    }

    private void setInstant(PreparedStatement statement, int index, Instant value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
            return;
        }
        statement.setObject(index, value.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
    }

    /** 可在指定 JDBC 事务连接中执行的测试写操作。 */
    @FunctionalInterface
    private interface SqlOperation {

        /** 执行写操作。 */
        void execute(Connection connection) throws SQLException;
    }
}
