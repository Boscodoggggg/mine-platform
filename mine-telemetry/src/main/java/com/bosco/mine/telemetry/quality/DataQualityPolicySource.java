package com.bosco.mine.telemetry.quality;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * 遥测批次读取最后正确数据质量策略的边界。
 */
@FunctionalInterface
public interface DataQualityPolicySource {

    /**
     * 批量读取设备当前策略，未配置设备不出现在结果中。
     *
     * @param deviceIds 本批设备标识
     * @return 设备策略映射
     */
    Map<UUID, DataQualityPolicy> findByDeviceIds(Collection<UUID> deviceIds);
}
