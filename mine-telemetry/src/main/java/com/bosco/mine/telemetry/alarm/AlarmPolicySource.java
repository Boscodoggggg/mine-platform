package com.bosco.mine.telemetry.alarm;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * 为遥测批次提供设备最后正确告警策略的读取边界。
 */
@FunctionalInterface
public interface AlarmPolicySource {

    /**
     * 批量读取设备告警策略。
     *
     * @param deviceIds 本批设备标识
     * @return 设备到告警策略的映射
     */
    Map<UUID, AlarmPolicy> findByDeviceIds(Collection<UUID> deviceIds);
}
