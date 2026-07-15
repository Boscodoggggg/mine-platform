package com.bosco.mine.server.modules.device;

import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.MineServerApplication;
import com.bosco.mine.server.modules.device.dto.DeviceResponse;
import com.bosco.mine.server.modules.device.dto.ProductResponse;
import com.bosco.mine.server.modules.device.service.DeviceCommandService;
import com.bosco.mine.server.modules.device.service.DeviceQueryService;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 PostgreSQL 校验产品、设备、归档和审计闭环。
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(
        classes = MineServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class DeviceManagementIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("mine")
            .withUsername("mine")
            .withPassword("mine-test-password");

    @Autowired
    private DeviceCommandService commandService;

    @Autowired
    private DeviceQueryService queryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 清理设备管理集成测试数据() {
        jdbcTemplate.update("DELETE FROM dev_device_credential");
        jdbcTemplate.update("DELETE FROM dev_device");
        jdbcTemplate.update("DELETE FROM dev_product");
        jdbcTemplate.update("DELETE FROM sys_operation_log");
    }

    @Test
    void 应完成产品设备创建查询修改归档和审计闭环() {
        ProductResponse product = commandService.createProduct(
                "haul-truck", "矿卡终端", "VEHICLE", "ACTIVE"
        );
        DeviceResponse device = commandService.createDevice(
                product.id(), "truck-001", "一号矿卡", "ENABLED"
        );

        ProductResponse updatedProduct = commandService.updateProduct(
                product.id(), "新能源矿卡终端", "VEHICLE", "ACTIVE", product.version()
        );
        DeviceResponse updatedDevice = commandService.updateDevice(
                device.id(), "一号新能源矿卡", "DISABLED", device.version()
        );

        assertThat(queryService.findProducts("新能源", "ACTIVE", 1, 20).items())
                .extracting(ProductResponse::id)
                .containsExactly(product.id());
        assertThat(queryService.findDevices(product.id(), "新能源", "DISABLED", 1, 20).items())
                .extracting(DeviceResponse::id)
                .containsExactly(device.id());

        commandService.archiveDevice(device.id(), updatedDevice.version());
        commandService.archiveProduct(product.id(), updatedProduct.version());

        assertThat(queryService.findDevices(product.id(), null, "ARCHIVED", 1, 20).items())
                .singleElement()
                .satisfies(item -> assertThat(item.archivedAt()).isNotNull());
        assertThat(jdbcTemplate.queryForList(
                "SELECT module || ':' || action FROM sys_operation_log ORDER BY occurred_at",
                String.class
        )).containsExactly(
                "DEVICE_MANAGEMENT:CREATE_PRODUCT",
                "DEVICE_MANAGEMENT:CREATE_DEVICE",
                "DEVICE_MANAGEMENT:UPDATE_PRODUCT",
                "DEVICE_MANAGEMENT:UPDATE_DEVICE",
                "DEVICE_MANAGEMENT:ARCHIVE_DEVICE",
                "DEVICE_MANAGEMENT:ARCHIVE_PRODUCT"
        );
    }

    @Test
    void 应拒绝重复编码和过期版本() {
        ProductResponse product = commandService.createProduct(
                "uwb-tag", "UWB 标签", "PERSON_TAG", "ACTIVE"
        );
        DeviceResponse device = commandService.createDevice(
                product.id(), "tag-001", "一号标签", "ENABLED"
        );

        assertThatThrownBy(() -> commandService.createProduct(
                "uwb-tag", "重复产品", "PERSON_TAG", "ACTIVE"
        )).isInstanceOfSatisfying(MineException.class, exception ->
                assertThat(exception.errorCode().code()).isEqualTo("DEVICE-409-001"));
        assertThatThrownBy(() -> commandService.createDevice(
                product.id(), "tag-001", "重复设备", "ENABLED"
        )).isInstanceOfSatisfying(MineException.class, exception ->
                assertThat(exception.errorCode().code()).isEqualTo("DEVICE-409-002"));

        commandService.updateDevice(device.id(), "更新标签", "ENABLED", device.version());
        assertThatThrownBy(() -> commandService.updateDevice(
                device.id(), "并发覆盖", "DISABLED", device.version()
        )).isInstanceOfSatisfying(MineException.class, exception ->
                assertThat(exception.errorCode().code()).isEqualTo("DEVICE-409-003"));
    }
}
