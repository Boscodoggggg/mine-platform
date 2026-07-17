package com.bosco.mine.server.modules.strategy;

import com.bosco.mine.common.error.MineException;
import com.bosco.mine.model.strategy.StrategyScopeType;
import com.bosco.mine.server.MineServerApplication;
import com.bosco.mine.server.modules.device.repository.DeviceMapper;
import com.bosco.mine.server.modules.strategy.repository.StrategyConfigurationMapper;
import com.bosco.mine.server.modules.strategy.repository.StrategyScopeMapper;
import com.bosco.mine.server.modules.strategy.service.DeviceGroupService;
import com.bosco.mine.server.modules.strategy.service.EffectiveOnlineStatusPolicyProvider;
import com.bosco.mine.server.modules.strategy.service.StrategyConfigurationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 PostgreSQL 校验策略发布、冲突门禁、有效值计算和不可变回滚链路。
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(
        classes = MineServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class StrategyConfigurationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("mine")
            .withUsername("mine")
            .withPassword("mine-test-password");

    @Autowired
    private DeviceMapper deviceMapper;

    @Autowired
    private StrategyScopeMapper scopeMapper;

    @Autowired
    private StrategyConfigurationMapper strategyMapper;

    @Autowired
    private StrategyConfigurationService strategyService;

    @Autowired
    private DeviceGroupService groupService;

    @Autowired
    private EffectiveOnlineStatusPolicyProvider policyProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 同优先级异值应回滚整个发布而同值应形成可追踪有效策略() {
        Fixture fixture = createFixture(800, 800);
        StrategyConfigurationService.StrategyVersionView firstDraft = createDraft(
                fixture.productId(), 120, "运输一组在线阈值"
        );
        StrategyConfigurationService.StrategyReleaseView firstRelease = strategyService.publish(
                publish(firstDraft.id(), fixture.firstGroupId(), "发布运输一组阈值", "publish-group-a")
        );
        StrategyConfigurationService.StrategyVersionView secondDraft = createDraft(
                fixture.productId(), 300, "运输二组在线阈值"
        );

        assertThatThrownBy(() -> strategyService.publish(
                publish(secondDraft.id(), fixture.secondGroupId(), "发布运输二组阈值", "publish-group-b-conflict")
        )).isInstanceOfSatisfying(MineException.class, exception -> {
            assertThat(exception.errorCode().code()).isEqualTo("STRATEGY-409-001");
            assertThat(exception.details()).containsEntry("conflictCount", 1L);
        });
        assertThat(strategyMapper.findVersionById(secondDraft.id())).get()
                .extracting(StrategyConfigurationMapper.StrategyVersionRecord::status)
                .isEqualTo("DRAFT");
        assertThat(releaseCount(fixture.productId())).isOne();

        StrategyConfigurationService.StrategyVersionView compatibleDraft = strategyService.updateDraft(
                secondDraft.id(),
                new StrategyConfigurationService.UpdateDraftCommand(
                        Map.of("offlineThresholdSeconds", 120), "改为同优先级兼容值", 0
                )
        );
        StrategyConfigurationService.StrategyReleaseView secondRelease = strategyService.publish(
                publish(compatibleDraft.id(), fixture.secondGroupId(), "发布兼容阈值", "publish-group-b")
        );
        StrategyConfigurationService.StrategyReleaseView replayedRelease = strategyService.publish(
                publish(compatibleDraft.id(), fixture.secondGroupId(), "发布兼容阈值", "publish-group-b")
        );
        StrategyConfigurationService.EffectiveStrategyView effective = strategyService.findEffective(
                fixture.deviceId(), "ONLINE_STATUS"
        );

        assertThat(replayedRelease.id()).isEqualTo(secondRelease.id());
        assertThat(effective.valid()).isTrue();
        assertThat(effective.values()).containsEntry("offlineThresholdSeconds", 120);
        assertThat(effective.contentHash()).startsWith("sha256:");
        assertThat(releaseCount(fixture.productId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM sys_operation_log
                 WHERE module = 'DEVICE_STRATEGY'
                   AND resource_id IN (?, ?, ?, ?, ?)
                """, Long.class,
                firstDraft.id().toString(), firstRelease.id().toString(),
                secondDraft.id().toString(), compatibleDraft.id().toString(), secondRelease.id().toString()
        )).isEqualTo(5L);
    }

    @Test
    void 数据质量策略应持久发布并阻止继承后的矛盾范围() {
        Fixture fixture = createFixture(800, 900);
        StrategyConfigurationService.StrategyVersionView productDraft =
                strategyService.createDraft(new StrategyConfigurationService.CreateDraftCommand(
                        fixture.productId(),
                        "DATA_QUALITY",
                        Map.of(
                                "staleAfterSeconds", 300,
                                "maximum.engine.rpm", new BigDecimal("100")
                        ),
                        "矿卡产品质量上限"
                ));
        StrategyConfigurationService.StrategyReleaseView productRelease = strategyService.publish(
                new StrategyConfigurationService.PublishCommand(
                        productDraft.id(), StrategyScopeType.PRODUCT, fixture.productId(),
                        "发布产品质量基线", "quality-product-release"
                )
        );
        StrategyConfigurationService.StrategyVersionView groupDraft =
                strategyService.createDraft(new StrategyConfigurationService.CreateDraftCommand(
                        fixture.productId(),
                        "DATA_QUALITY",
                        Map.of("minimum.engine.rpm", new BigDecimal("200")),
                        "运输组质量下限"
                ));

        assertThatThrownBy(() -> strategyService.publish(
                new StrategyConfigurationService.PublishCommand(
                        groupDraft.id(), StrategyScopeType.GROUP, fixture.firstGroupId(),
                        "发布运输组质量覆盖", "quality-group-release"
                )
        )).isInstanceOfSatisfying(MineException.class, exception -> {
            assertThat(exception.errorCode().code()).isEqualTo("STRATEGY-422-004");
            assertThat(exception.details()).containsEntry("deviceId", fixture.deviceId());
        });

        StrategyConfigurationService.EffectiveStrategyView effective = strategyService.findEffective(
                fixture.deviceId(), "DATA_QUALITY"
        );
        assertThat(productRelease.strategyType()).isEqualTo("DATA_QUALITY");
        assertThat(effective.values())
                .containsEntry("staleAfterSeconds", 300)
                .containsEntry("maximum.engine.rpm", new BigDecimal("1E+2"));
        assertThat(releaseCount(fixture.productId())).isOne();
        assertThat(strategyMapper.findVersionById(groupDraft.id())).get()
                .extracting(StrategyConfigurationMapper.StrategyVersionRecord::status)
                .isEqualTo("DRAFT");
    }

    @Test
    void 设备组优先级变化应经过冲突门禁并让回滚使用当前优先级() {
        Fixture fixture = createFixture(800, 900);
        StrategyConfigurationService.StrategyVersionView firstDraft = createDraft(
                fixture.productId(), 120, "运输一组在线阈值"
        );
        StrategyConfigurationService.StrategyReleaseView firstRelease = strategyService.publish(
                publish(firstDraft.id(), fixture.firstGroupId(), "发布运输一组阈值", "publish-priority-a")
        );
        StrategyConfigurationService.StrategyVersionView secondDraft = createDraft(
                fixture.productId(), 300, "运输二组在线阈值"
        );
        strategyService.publish(
                publish(secondDraft.id(), fixture.secondGroupId(), "发布运输二组阈值", "publish-priority-b")
        );
        assertThat(policyProvider.resolveThresholds(List.of(fixture.deviceId())))
                .containsEntry(fixture.deviceId(), 300);

        assertThatThrownBy(() -> groupService.updateGroup(
                fixture.firstGroupId(),
                new DeviceGroupService.UpdateGroupCommand("运输一组", 900, "同级冲突", 0)
        )).isInstanceOfSatisfying(MineException.class, exception ->
                assertThat(exception.errorCode().code()).isEqualTo("STRATEGY-409-001"));
        assertThat(scopeMapper.findGroupById(fixture.firstGroupId())).get()
                .satisfies(group -> {
                    assertThat(group.priority()).isEqualTo(800);
                    assertThat(group.version()).isZero();
                });

        DeviceGroupService.DeviceGroupView updated = groupService.updateGroup(
                fixture.firstGroupId(),
                new DeviceGroupService.UpdateGroupCommand("运输一组", 1000, "最高优先级", 0)
        );
        assertThat(updated.priority()).isEqualTo(1000);
        assertThat(policyProvider.resolveThresholds(List.of(fixture.deviceId())))
                .containsEntry(fixture.deviceId(), 120);
        StrategyConfigurationService.EffectiveStrategyView effective = strategyService.findEffective(
                fixture.deviceId(), "ONLINE_STATUS"
        );
        assertThat(effective.values()).containsEntry("offlineThresholdSeconds", 120);
        assertThat(effective.sources().get("offlineThresholdSeconds").releaseId())
                .isEqualTo(firstRelease.id());

        StrategyConfigurationService.StrategyReleaseView rollback = strategyService.rollback(
                firstRelease.id(), "验证不可变版本回滚", "rollback-priority-a"
        );
        assertThat(rollback.releaseSequence()).isEqualTo(3);
        assertThat(rollback.groupPriority()).isEqualTo(1000);
        assertThat(rollback.rollbackOfReleaseId()).isEqualTo(firstRelease.id());
        assertThat(strategyMapper.findRelease(firstRelease.id())).get()
                .extracting(StrategyConfigurationMapper.StrategyReleaseRecord::groupPriority)
                .isEqualTo(800);
        assertThat(strategyService.findEffective(fixture.deviceId(), "ONLINE_STATUS")
                .sources().get("offlineThresholdSeconds").releaseId()).isEqualTo(rollback.id());
    }

    private StrategyConfigurationService.StrategyVersionView createDraft(
            UUID productId,
            int thresholdSeconds,
            String description
    ) {
        return strategyService.createDraft(new StrategyConfigurationService.CreateDraftCommand(
                productId,
                "ONLINE_STATUS",
                Map.of("offlineThresholdSeconds", thresholdSeconds),
                description
        ));
    }

    private StrategyConfigurationService.PublishCommand publish(
            UUID versionId,
            UUID groupId,
            String reason,
            String idempotencyKey
    ) {
        return new StrategyConfigurationService.PublishCommand(
                versionId, StrategyScopeType.GROUP, groupId, reason, idempotencyKey
        );
    }

    private Fixture createFixture(int firstPriority, int secondPriority) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID productId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID firstGroupId = UUID.randomUUID();
        UUID secondGroupId = UUID.randomUUID();
        deviceMapper.insertProduct(
                productId, "truck-" + suffix, "矿卡终端", "VEHICLE", "ACTIVE", null
        );
        deviceMapper.insertDevice(
                deviceId, productId, "truck-device-" + suffix, "一号矿卡", "ENABLED", null
        );
        scopeMapper.insertGroup(
                firstGroupId, productId, "haul-a", "运输一组", firstPriority, null, null
        );
        scopeMapper.insertGroup(
                secondGroupId, productId, "haul-b", "运输二组", secondPriority, null, null
        );
        scopeMapper.insertMemberships(firstGroupId, List.of(
                new StrategyScopeMapper.MembershipInsert(UUID.randomUUID(), deviceId)
        ), null);
        scopeMapper.insertMemberships(secondGroupId, List.of(
                new StrategyScopeMapper.MembershipInsert(UUID.randomUUID(), deviceId)
        ), null);
        return new Fixture(productId, deviceId, firstGroupId, secondGroupId);
    }

    private long releaseCount(UUID productId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dev_strategy_release WHERE product_id = ?",
                Long.class,
                productId
        );
    }

    private record Fixture(
            UUID productId,
            UUID deviceId,
            UUID firstGroupId,
            UUID secondGroupId
    ) {
    }
}
