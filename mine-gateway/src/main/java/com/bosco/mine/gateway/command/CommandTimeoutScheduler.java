package com.bosco.mine.gateway.command;

import java.time.Instant;
import java.util.UUID;

/**
 * 为已发送命令登记一次性回复超时事件。
 */
@FunctionalInterface
public interface CommandTimeoutScheduler {

    /**
     * 登记命令超时标记，只有 RabbitMQ 已确认持久化后才能正常返回。
     *
     * @param commandId 命令标识
     * @param deviceId 设备标识
     * @param timeoutAt 回复截止时间
     * @param traceId 链路追踪标识
     * @param tenantScope 矿区范围
     */
    void schedule(UUID commandId, UUID deviceId, Instant timeoutAt, String traceId, String tenantScope);
}
