package com.bosco.mine.gateway.command;

import com.bosco.mine.messaging.command.CommandStatusPayload;

/**
 * 命令状态事件可靠发布边界。
 */
@FunctionalInterface
public interface CommandStatusEventPublisher {

    /**
     * 发布状态事件，只有平台 RabbitMQ 已确认时才能正常返回。
     *
     * @param payload 命令状态载荷
     * @param traceId 链路追踪标识
     * @param tenantScope 矿区隔离范围
     */
    void publish(CommandStatusPayload payload, String traceId, String tenantScope);
}
