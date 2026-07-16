package com.bosco.mine.server.modules.device.infrastructure;

import java.time.Instant;
import java.util.UUID;

/**
 * 查询设备当前传输会话的诊断端口。
 */
public interface DeviceTransportSessionProbe {

    /**
     * 查询指定设备和连接方式的会话证据。
     *
     * @param deviceId 设备标识
     * @param connectionMode 连接方式
     * @return 脱敏会话检查结果
     */
    Result probe(UUID deviceId, String connectionMode);

    /**
     * 传输会话检查结果。
     *
     * @param status 检查状态
     * @param message 稳定原因码
     * @param observedAt 会话证据时间
     */
    record Result(String status, String message, Instant observedAt) {
    }
}
