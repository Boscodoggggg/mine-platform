package com.bosco.mine.model.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 表示 gateway 输出的、与厂商原始字段解耦的统一设备消息。
 *
 * @param messageId       UUIDv7 消息标识，重试时保持不变
 * @param messageType     统一消息类型
 * @param productKey      产品标识
 * @param deviceId        设备标识
 * @param protocolVersion 实际解析该消息的协议版本
 * @param bootId          设备启动周期标识，不支持时可为空
 * @param sequence        设备启动周期内序号，不支持时可为空
 * @param occurredAt      设备声明的发生时间
 * @param receivedAt      平台接收时间
 * @param replayed        是否为弱网补传数据
 * @param properties      标准遥测属性
 * @param quality         整帧数据质量
 * @param rawRef          原始报文对象引用，可为空
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UnifiedDeviceMessage(
        UUID messageId,
        MessageKind messageType,
        String productKey,
        String deviceId,
        String protocolVersion,
        String bootId,
        Long sequence,
        Instant occurredAt,
        Instant receivedAt,
        boolean replayed,
        TelemetryPayload properties,
        Quality quality,
        String rawRef) {

    /**
     * 创建并校验统一设备消息。
     */
    public UnifiedDeviceMessage {
        Objects.requireNonNull(messageId, "messageId 不能为空");
        if (messageId.version() != 7) {
            throw new IllegalArgumentException("messageId 必须是 UUIDv7");
        }
        Objects.requireNonNull(messageType, "messageType 不能为空");
        productKey = requireText(productKey, "productKey");
        deviceId = requireText(deviceId, "deviceId");
        protocolVersion = requireText(protocolVersion, "protocolVersion");
        Objects.requireNonNull(occurredAt, "occurredAt 不能为空");
        Objects.requireNonNull(receivedAt, "receivedAt 不能为空");
        Objects.requireNonNull(properties, "properties 不能为空");
        Objects.requireNonNull(quality, "quality 不能为空");
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " 不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空白字符串");
        }
        return value;
    }

    /**
     * 统一设备消息类型。
     */
    public enum MessageKind {
        /** 遥测属性上报。 */
        TELEMETRY,
        /** 设备事件上报。 */
        EVENT,
        /** 定位数据上报。 */
        LOCATION,
        /** 设备状态变化。 */
        STATUS,
        /** 命令执行回复。 */
        COMMAND_REPLY
    }

    /**
     * 统一数据质量等级。
     */
    public enum Quality {
        /** 数据通过全部质量校验。 */
        GOOD,
        /** 数据仍可使用，但存在质量警告。 */
        UNCERTAIN,
        /** 数据无效，不应用于业务计算。 */
        BAD,
        /** 设备时钟不可作为可信时间源。 */
        TIME_UNTRUSTED
    }
}
