package com.bosco.mine.server.modules.device;

import com.bosco.mine.server.MineServerApplication;
import com.bosco.mine.server.modules.device.model.DeviceConnectionProfile;
import com.bosco.mine.server.modules.device.repository.DeviceConnectionProfileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 PostgreSQL 校验设备接入配置的约束和中文注释。
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(
        classes = MineServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class DeviceConnectionProfileRepositoryIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("mine")
            .withUsername("mine")
            .withPassword("mine-test-password");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DeviceConnectionProfileMapper profileMapper;

    private UUID deviceId;

    @BeforeEach
    void 创建设备接入配置测试数据() {
        jdbcTemplate.update("DELETE FROM dev_device_connection_profile");
        jdbcTemplate.update("DELETE FROM dev_device_credential");
        jdbcTemplate.update("DELETE FROM dev_device");
        jdbcTemplate.update("DELETE FROM dev_product");
        UUID productId = UUID.randomUUID();
        deviceId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO dev_product (id, product_key, name, device_type, status)
                VALUES (?, 'haul-truck', '矿卡终端', 'VEHICLE', 'ACTIVE')
                """, productId);
        jdbcTemplate.update("""
                INSERT INTO dev_device (id, product_id, device_code, name, status)
                VALUES (?, ?, 'truck-001', '一号矿卡', 'ENABLED')
                """, deviceId, productId);
    }

    @Test
    void 每台设备只能保存一份有效接入配置且端口必须合法() {
        insertProfile(UUID.randomUUID(), deviceId, 8883);

        assertThatThrownBy(() -> insertProfile(UUID.randomUUID(), deviceId, 1883))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertProfile(UUID.randomUUID(), UUID.randomUUID(), 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 接入配置表和每个字段都必须具有中文注释() {
        String tableComment = jdbcTemplate.queryForObject("""
                SELECT obj_description('dev_device_connection_profile'::regclass, 'pg_class')
                """, String.class);
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("""
                SELECT a.attname AS column_name,
                       col_description(a.attrelid, a.attnum) AS column_comment
                FROM pg_attribute a
                WHERE a.attrelid = 'dev_device_connection_profile'::regclass
                  AND a.attnum > 0
                  AND NOT a.attisdropped
                ORDER BY a.attnum
                """);

        assertThat(tableComment).isNotBlank();
        assertThat(columns).isNotEmpty().allSatisfy(column ->
                assertThat(column.get("column_comment")).as(String.valueOf(column.get("column_name"))).isNotNull()
        );
    }

    @Test
    void 数据接口应保存并使用乐观锁更新诊断结果() {
        Instant now = Instant.parse("2026-07-16T00:00:00Z");
        UUID profileId = UUID.randomUUID();
        DeviceConnectionProfile profile = new DeviceConnectionProfile(
                profileId, deviceId, "DEVICE_TO_PLATFORM_MQTT", "mqtt.mine.local", 8883,
                true, "mqtt.mine.local", 5000, 60, 180,
                "NOT_TESTED", null, null, null, "[]", now, now, 0
        );

        assertThat(profileMapper.insert(profile, null)).isOne();
        assertThat(profileMapper.findByDeviceId(deviceId)).get().satisfies(saved -> {
            assertThat(saved.host()).isEqualTo("mqtt.mine.local");
            assertThat(saved.lastTestStages()).isEqualTo("[]");
        });
        assertThat(profileMapper.updateTestResult(
                profileId, "PARTIAL", now, 12, "等待首帧", "[]", 0, null
        )).isOne();
        assertThat(profileMapper.findByDeviceId(deviceId)).get().satisfies(saved -> {
            assertThat(saved.lastTestStatus()).isEqualTo("PARTIAL");
            assertThat(saved.version()).isOne();
        });
    }

    private void insertProfile(UUID id, UUID targetDeviceId, int port) {
        jdbcTemplate.update("""
                INSERT INTO dev_device_connection_profile (
                    id, device_id, connection_mode, host, port, tls_enabled,
                    connect_timeout_millis, keep_alive_seconds, offline_threshold_seconds
                ) VALUES (?, ?, 'DEVICE_TO_PLATFORM_MQTT', 'mqtt.mine.local', ?, TRUE, 5000, 60, 180)
                """, id, targetDeviceId, port);
    }
}
