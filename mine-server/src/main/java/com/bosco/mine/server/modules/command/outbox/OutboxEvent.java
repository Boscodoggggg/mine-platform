package com.bosco.mine.server.modules.command.outbox;

import java.util.Objects;
import java.util.UUID;
import java.time.Instant;

/**
 * 已被当前服务实例认领、等待发布的 Outbox 事件。
 *
 * @param eventId 事件标识
 * @param aggregateId 聚合标识
 * @param eventType 事件类型
 * @param schemaVersion 结构版本
 * @param payloadJson 业务载荷 JSON
 * @param headersJson 链路头 JSON
 * @param attempts 包含本次认领在内的尝试次数
 * @param occurredAt 业务事件创建时间
 */
public record OutboxEvent(
        UUID eventId,
        UUID aggregateId,
        String eventType,
        int schemaVersion,
        String payloadJson,
        String headersJson,
        int attempts,
        Instant occurredAt
) {

    /**
     * 校验认领结果不可缺少可靠发布所需信息。
     */
    public OutboxEvent {
        Objects.requireNonNull(eventId, "事件标识不能为空");
        Objects.requireNonNull(aggregateId, "聚合标识不能为空");
        Objects.requireNonNull(eventType, "事件类型不能为空");
        Objects.requireNonNull(payloadJson, "事件载荷不能为空");
        Objects.requireNonNull(headersJson, "事件头不能为空");
        Objects.requireNonNull(occurredAt, "事件创建时间不能为空");
        if (schemaVersion < 1 || attempts < 1) {
            throw new IllegalArgumentException("事件版本和尝试次数必须大于 0");
        }
    }
}
