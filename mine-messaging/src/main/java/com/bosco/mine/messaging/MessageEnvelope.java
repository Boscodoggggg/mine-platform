package com.bosco.mine.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 平台内部事件的版本化统一消息信封。
 *
 * @param eventId       UUIDv7 事件标识，重试时保持不变
 * @param eventType     决定消息语义的稳定事件类型
 * @param schemaVersion 事件结构版本，从 1 开始
 * @param occurredAt    业务事实发生时间
 * @param publishedAt   生产者发布时间
 * @param producer      生产该事件的服务
 * @param traceId       跨同步和异步链路传递的追踪标识
 * @param correlationId 相关命令、会话或业务流程标识，可为空
 * @param partitionKey  设备维度有序与路由使用的分区键
 * @param tenantScope   矿区隔离范围
 * @param payload       与事件类型和结构版本对应的载荷
 * @param <T>           载荷类型
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MessageEnvelope<T>(
        UUID eventId,
        MessageType eventType,
        int schemaVersion,
        Instant occurredAt,
        Instant publishedAt,
        String producer,
        String traceId,
        String correlationId,
        String partitionKey,
        String tenantScope,
        T payload) {

    /**
     * 创建并校验统一消息信封。
     */
    public MessageEnvelope {
        Objects.requireNonNull(eventId, "eventId 不能为空");
        if (eventId.version() != 7) {
            throw new IllegalArgumentException("eventId 必须是 UUIDv7");
        }
        Objects.requireNonNull(eventType, "eventType 不能为空");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion 必须大于等于 1");
        }
        Objects.requireNonNull(occurredAt, "occurredAt 不能为空");
        Objects.requireNonNull(publishedAt, "publishedAt 不能为空");
        producer = requireText(producer, "producer");
        traceId = requireText(traceId, "traceId");
        partitionKey = requireText(partitionKey, "partitionKey");
        tenantScope = requireText(tenantScope, "tenantScope");
        Objects.requireNonNull(payload, "payload 不能为空");
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " 不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空白字符串");
        }
        return value;
    }
}
