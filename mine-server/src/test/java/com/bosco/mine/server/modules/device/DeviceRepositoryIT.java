package com.bosco.mine.server.modules.device;

import com.bosco.mine.server.MineServerApplication;
import com.bosco.mine.server.modules.device.repository.DeviceMapper;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 PostgreSQL 校验产品、设备和设备凭据的数据约束。
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(
        classes = MineServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class DeviceRepositoryIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("mine")
            .withUsername("mine")
            .withPassword("mine-test-password");

    @Autowired
    private DeviceMapper mapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 清理设备测试数据() {
        jdbcTemplate.update("DELETE FROM dev_device_credential");
        jdbcTemplate.update("DELETE FROM dev_device");
        jdbcTemplate.update("DELETE FROM dev_product");
    }

    @Test
    void 产品编码和设备编码必须在矿区内唯一() {
        UUID productId = UUID.randomUUID();
        mapper.insertProduct(productId, "haul-truck", "矿卡终端", "VEHICLE", "ACTIVE", null);
        mapper.insertDevice(UUID.randomUUID(), productId, "truck-001", "一号矿卡", "ENABLED", null);

        assertThatThrownBy(() -> mapper.insertProduct(
                UUID.randomUUID(), "haul-truck", "重复产品", "VEHICLE", "ACTIVE", null
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> mapper.insertDevice(
                UUID.randomUUID(), productId, "truck-001", "重复设备", "ENABLED", null
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 归档设备应保留台账记录且不能再作为活动设备查询() {
        UUID productId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        mapper.insertProduct(productId, "uwb-tag", "UWB 标签", "PERSON_TAG", "ACTIVE", null);
        mapper.insertDevice(deviceId, productId, "tag-001", "一号标签", "ENABLED", null);

        assertThat(mapper.archiveDevice(deviceId, 0, null)).isOne();
        assertThat(mapper.findDeviceById(deviceId)).get().satisfies(device -> {
            assertThat(device.status()).isEqualTo("ARCHIVED");
            assertThat(device.archivedAt()).isNotNull();
            assertThat(device.version()).isOne();
        });
        assertThat(mapper.findActiveDeviceByCode("tag-001")).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dev_device WHERE id = ?",
                Long.class,
                deviceId
        )).isOne();
    }

    @Test
    void 设备凭据表只能保存不可逆散列和安全摘要() {
        List<String> columns = jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'dev_device_credential'
                ORDER BY ordinal_position
                """, String.class);

        assertThat(columns).contains("secret_hash", "secret_hint");
        assertThat(columns).doesNotContain("secret", "password", "plain_secret", "plaintext_secret");
    }
}
