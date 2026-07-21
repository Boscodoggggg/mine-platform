package com.bosco.mine.server.modules.strategy.service;

import com.bosco.mine.server.modules.strategy.repository.StrategyScopeMapper;

import java.util.List;
import java.util.UUID;

/**
 * 在设备组作用范围变化前校验当前已发布策略不会形成同优先级冲突。
 */
public interface StrategyConflictGuard {

    /**
     * 校验新增成员继承设备组当前策略后仍可确定性解析。
     *
     * @param group          已锁定设备组
     * @param addedDeviceIds 新增成员设备标识
     */
    void verifyMembershipChange(
            StrategyScopeMapper.DeviceGroupRecord group,
            List<UUID> addedDeviceIds
    );

    /**
     * 校验设备组优先级变化后的全部当前成员策略。
     *
     * @param group           已锁定设备组
     * @param newPriority     新优先级
     * @param currentDeviceIds 当前成员设备标识
     */
    void verifyPriorityChange(
            StrategyScopeMapper.DeviceGroupRecord group,
            int newPriority,
            List<UUID> currentDeviceIds
    );
}
