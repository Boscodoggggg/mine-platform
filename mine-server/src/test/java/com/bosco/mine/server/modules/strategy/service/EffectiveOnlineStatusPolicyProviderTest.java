package com.bosco.mine.server.modules.strategy.service;

import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.modules.strategy.repository.StrategyConfigurationMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验在线状态有效策略的批量解析、缓存和故障保底语义。
 */
class EffectiveOnlineStatusPolicyProviderTest {

    private StrategyConfigurationMapper mapper;
    private SimpleMeterRegistry meterRegistry;
    private EffectiveOnlineStatusPolicyProvider provider;
    private UUID productId;
    private UUID firstDeviceId;
    private UUID secondDeviceId;

    @BeforeEach
    void 创建有效在线策略提供器测试环境() {
        mapper = mock(StrategyConfigurationMapper.class);
        meterRegistry = new SimpleMeterRegistry();
        provider = new EffectiveOnlineStatusPolicyProvider(
                mapper,
                JsonMapper.builder().findAndAddModules().build(),
                meterRegistry,
                100
        );
        productId = UUID.randomUUID();
        firstDeviceId = UUID.randomUUID();
        secondDeviceId = UUID.randomUUID();
    }

    @AfterEach
    void 清理事务同步上下文() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void 同产品多设备应批量查询并缓存有效离线阈值() {
        List<UUID> deviceIds = List.of(firstDeviceId, secondDeviceId);
        when(mapper.findDeviceContexts(deviceIds)).thenReturn(List.of(
                context(firstDeviceId), context(secondDeviceId)
        ));
        when(mapper.findCurrentFragmentsForDevices(productId, "ONLINE_STATUS", deviceIds))
                .thenReturn(List.of(
                        fragment(firstDeviceId, 90),
                        fragment(secondDeviceId, 300)
                ));

        Map<UUID, Integer> first = provider.resolveThresholds(deviceIds);
        Map<UUID, Integer> cached = provider.resolveThresholds(deviceIds);

        assertThat(first).containsEntry(firstDeviceId, 90).containsEntry(secondDeviceId, 300);
        assertThat(cached).isEqualTo(first);
        verify(mapper).findDeviceContexts(deviceIds);
        verify(mapper).findCurrentFragmentsForDevices(productId, "ONLINE_STATUS", deviceIds);
        assertThat(meterRegistry.get("mine.strategy.cache.access")
                .tag("strategy_type", "ONLINE_STATUS")
                .tag("result", "hit").counter().count()).isEqualTo(2);
    }

    @Test
    void 发布后刷新损坏时应继续使用上一份已知良好值并暴露指标() {
        List<UUID> deviceIds = List.of(firstDeviceId);
        when(mapper.findDeviceContexts(deviceIds)).thenReturn(List.of(context(firstDeviceId)));
        when(mapper.findCurrentFragmentsForDevices(productId, "ONLINE_STATUS", deviceIds))
                .thenReturn(List.of(fragment(firstDeviceId, 120)))
                .thenReturn(List.of(new StrategyConfigurationMapper.StrategyFragmentRecord(
                        firstDeviceId, UUID.randomUUID(), "PRODUCT", productId, null,
                        "{\"offlineThresholdSeconds\":\"损坏值\"}"
                )));
        assertThat(provider.resolveThresholds(deviceIds)).containsEntry(firstDeviceId, 120);

        registerAndCommitInvalidation(deviceIds);
        Map<UUID, Integer> fallback = provider.resolveThresholds(deviceIds);

        assertThat(fallback).containsEntry(firstDeviceId, 120);
        verify(mapper, times(2)).findCurrentFragmentsForDevices(
                productId, "ONLINE_STATUS", deviceIds);
        assertThat(meterRegistry.get("mine.strategy.resolution")
                .tag("strategy_type", "ONLINE_STATUS")
                .tag("result", "stale_last_good").counter().count()).isEqualTo(1);
    }

    @Test
    void 首次解析已发布损坏策略时不得静默回退连接配置() {
        List<UUID> deviceIds = List.of(firstDeviceId);
        when(mapper.findDeviceContexts(deviceIds)).thenReturn(List.of(context(firstDeviceId)));
        when(mapper.findCurrentFragmentsForDevices(productId, "ONLINE_STATUS", deviceIds))
                .thenReturn(List.of(new StrategyConfigurationMapper.StrategyFragmentRecord(
                        firstDeviceId, UUID.randomUUID(), "PRODUCT", productId, null,
                        "{\"offlineThresholdSeconds\":0}"
                )));

        assertThatThrownBy(() -> provider.resolveThresholds(deviceIds))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("STRATEGY-500-001"));
    }

    private void registerAndCommitInvalidation(List<UUID> deviceIds) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        provider.invalidateAfterCommit(
                "ONLINE_STATUS",
                deviceIds,
                EffectiveStrategyCacheInvalidator.InvalidationReason.PUBLISH
        );
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        synchronizations.forEach(TransactionSynchronization::afterCommit);
    }

    private StrategyConfigurationMapper.DeviceContextRecord context(UUID deviceId) {
        return new StrategyConfigurationMapper.DeviceContextRecord(deviceId, productId, "ENABLED");
    }

    private StrategyConfigurationMapper.StrategyFragmentRecord fragment(UUID deviceId, int threshold) {
        return new StrategyConfigurationMapper.StrategyFragmentRecord(
                deviceId, UUID.randomUUID(), "PRODUCT", productId, null,
                "{\"offlineThresholdSeconds\":" + threshold + "}"
        );
    }
}
