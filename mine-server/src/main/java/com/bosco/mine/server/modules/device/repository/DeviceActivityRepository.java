package com.bosco.mine.server.modules.device.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * 查询已持久化的设备有效活动证据。
 */
public interface DeviceActivityRepository {

    /**
     * 批量查询设备活动证据。
     *
     * @param deviceIds 设备标识集合
     * @return 以设备标识为键的活动证据
     */
    Map<UUID, ActivityEvidence> findByDeviceIds(Collection<UUID> deviceIds);

    /**
     * 设备活动证据。
     *
     * @param firstSeenAt 有效首帧接收时间
     * @param lastReceivedAt 最近有效报文接收时间
     */
    record ActivityEvidence(Instant firstSeenAt, Instant lastReceivedAt) {
    }
}
