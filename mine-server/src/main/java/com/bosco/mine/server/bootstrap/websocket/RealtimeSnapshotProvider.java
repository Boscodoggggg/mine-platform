package com.bosco.mine.server.bootstrap.websocket;

import com.bosco.mine.server.modules.device.dto.DeviceLatestResponse;

import java.util.List;
import java.util.UUID;

/**
 * 按订阅条件读取一次设备最新值快照。
 */
@FunctionalInterface
public interface RealtimeSnapshotProvider {

    /**
     * 读取设备指定属性的当前快照。
     *
     * @param deviceId 设备标识
     * @param propertyIds 属性标识集合
     * @return 设备最新值快照
     */
    DeviceLatestResponse load(UUID deviceId, List<String> propertyIds);
}
