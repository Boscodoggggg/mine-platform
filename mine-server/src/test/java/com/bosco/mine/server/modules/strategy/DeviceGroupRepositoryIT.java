package com.bosco.mine.server.modules.strategy;

import com.bosco.mine.server.MineServerApplication;
import com.bosco.mine.server.modules.device.repository.DeviceMapper;
import com.bosco.mine.server.modules.strategy.repository.StrategyScopeMapper;
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
 * 使用真实 PostgreSQL 校验设备组动态 SQL、跨产品防线和成员生命周期。
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(
        classes = MineServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class DeviceGroupRepositoryIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("mine")
            .withUsername("mine")
            .withPassword("mine-test-password");

    @Autowired
    private DeviceMapper deviceMapper;

    @Autowired
    private StrategyScopeMapper strategyScopeMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 清理设备组测试数据() {
        jdbcTemplate.update("DELETE FROM dev_strategy_release");
        jdbcTemplate.update("DELETE FROM dev_strategy_version");
        jdbcTemplate.update("DELETE FROM dev_device_group_membership");
        jdbcTemplate.update("DELETE FROM dev_device_group");
        jdbcTemplate.update("DELETE FROM dev_device_credential");
        jdbcTemplate.update("DELETE FROM dev_device");
        jdbcTemplate.update("DELETE FROM dev_product");
    }

    @Test
    void 设备组查询和成员历史SQL应在真实数据库正确执行() {
        UUID productId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID retainedDeviceId = UUID.randomUUID();
        UUID removedDeviceId = UUID.randomUUID();
        deviceMapper.insertProduct(productId, "haul-truck", "矿卡终端", "VEHICLE", "ACTIVE", null);
        deviceMapper.insertDevice(retainedDeviceId, productId, "truck-001", "一号矿卡", "ENABLED", null);
        deviceMapper.insertDevice(removedDeviceId, productId, "truck-002", "二号矿卡", "ENABLED", null);

        strategyScopeMapper.insertGroup(
                groupId, productId, "haul-team", "运输一组", 800, "主采区运输车辆", null
        );
        strategyScopeMapper.insertMemberships(groupId, List.of(
                new StrategyScopeMapper.MembershipInsert(UUID.randomUUID(), retainedDeviceId),
                new StrategyScopeMapper.MembershipInsert(UUID.randomUUID(), removedDeviceId)
        ), null);

        assertThat(strategyScopeMapper.countGroups(productId, "运输", "ACTIVE")).isOne();
        assertThat(strategyScopeMapper.findGroups(productId, null, "ACTIVE", 0, 20))
                .singleElement()
                .satisfies(group -> {
                    assertThat(group.groupKey()).isEqualTo("haul-team");
                    assertThat(group.memberCount()).isEqualTo(2);
                });
        assertThat(strategyScopeMapper.findActiveMembers(groupId))
                .extracting(StrategyScopeMapper.DeviceGroupMemberRecord::deviceCode)
                .containsExactly("truck-001", "truck-002");
        assertThat(strategyScopeMapper.findGroupByIdForUpdate(groupId)).get()
                .extracting(StrategyScopeMapper.DeviceGroupRecord::version)
                .isEqualTo(0L);

        assertThat(strategyScopeMapper.closeMemberships(groupId, List.of(removedDeviceId), null)).isOne();
        assertThat(strategyScopeMapper.findActiveMemberIds(groupId)).containsExactly(retainedDeviceId);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM dev_device_group_membership
                 WHERE group_id = ?
                   AND device_id = ?
                   AND effective_to IS NOT NULL
                """, Long.class, groupId, removedDeviceId)).isOne();
    }

    @Test
    void 数据库应拒绝跨产品成员并在设备归档时自动结束关系() {
        UUID productId = UUID.randomUUID();
        UUID foreignProductId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID localDeviceId = UUID.randomUUID();
        UUID foreignDeviceId = UUID.randomUUID();
        deviceMapper.insertProduct(productId, "haul-truck", "矿卡终端", "VEHICLE", "ACTIVE", null);
        deviceMapper.insertProduct(foreignProductId, "uwb-tag", "UWB 标签", "SENSOR", "ACTIVE", null);
        deviceMapper.insertDevice(localDeviceId, productId, "truck-001", "一号矿卡", "ENABLED", null);
        deviceMapper.insertDevice(foreignDeviceId, foreignProductId, "tag-001", "一号标签", "ENABLED", null);
        strategyScopeMapper.insertGroup(groupId, productId, "haul-team", "运输一组", 800, null, null);
        strategyScopeMapper.insertMemberships(groupId, List.of(
                new StrategyScopeMapper.MembershipInsert(UUID.randomUUID(), localDeviceId)
        ), null);

        assertThatThrownBy(() -> strategyScopeMapper.insertMemberships(groupId, List.of(
                new StrategyScopeMapper.MembershipInsert(UUID.randomUUID(), foreignDeviceId)
        ), null)).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(deviceMapper.archiveDevice(localDeviceId, 0, null)).isOne();
        assertThat(strategyScopeMapper.findActiveMemberIds(groupId)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM dev_device_group_membership
                 WHERE group_id = ?
                   AND device_id = ?
                   AND effective_to IS NOT NULL
                """, Long.class, groupId, localDeviceId)).isOne();
        assertThat(strategyScopeMapper.findGroupById(groupId)).get()
                .extracting(StrategyScopeMapper.DeviceGroupRecord::version)
                .isEqualTo(1L);
    }
}
