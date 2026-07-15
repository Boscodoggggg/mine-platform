package com.bosco.mine.telemetry.pipeline;

import com.bosco.mine.model.message.UnifiedDeviceMessage.MessageKind;
import com.bosco.mine.model.message.UnifiedDeviceMessage.Quality;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 表示完成协议归一化和时间判定后，可供最新值与历史存储共同使用的单个数值遥测点。
 *
 * @param eventId     平台统一事件标识
 * @param deviceId    平台内部设备标识
 * @param productKey  产品标识
 * @param propertyId  物模型属性标识
 * @param value       数值型属性值
 * @param quality     数据质量
 * @param bootId      设备启动周期标识，不支持时可为空
 * @param sequence    启动周期内序号，不支持时可为空
 * @param messageType 统一消息类型
 * @param occurredAt  设备声明的发生时间
 * @param receivedAt  平台接收时间
 * @param effectiveAt 经过时间质量策略判定后的排序时间
 */
public record TelemetrySample(
        UUID eventId,
        UUID deviceId,
        String productKey,
        String propertyId,
        double value,
        Quality quality,
        String bootId,
        Long sequence,
        MessageKind messageType,
        Instant occurredAt,
        Instant receivedAt,
        Instant effectiveAt) {

    /**
     * 创建并校验标准遥测点。
     */
    public TelemetrySample {
        eventId = Objects.requireNonNull(eventId, "事件标识不能为空");
        deviceId = Objects.requireNonNull(deviceId, "设备标识不能为空");
        if (eventId.version() != 7) {
            throw new IllegalArgumentException("事件标识必须是 UUIDv7");
        }
        productKey = requireText(productKey, "产品标识", 64);
        propertyId = requireText(propertyId, "属性标识", 128);
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("遥测值必须是有限数值");
        }
        quality = Objects.requireNonNull(quality, "数据质量不能为空");
        if (bootId != null) {
            bootId = requireText(bootId, "设备启动标识", 64);
        }
        if (sequence != null && sequence < 0L) {
            throw new IllegalArgumentException("设备序号不能为负数");
        }
        messageType = Objects.requireNonNull(messageType, "消息类型不能为空");
        occurredAt = Objects.requireNonNull(occurredAt, "设备发生时间不能为空");
        receivedAt = Objects.requireNonNull(receivedAt, "平台接收时间不能为空");
        effectiveAt = Objects.requireNonNull(effectiveAt, "有效排序时间不能为空");
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + "不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空白字符串");
        }
        if (value.length() > maximumLength) {
            throw new IllegalArgumentException(name + "长度不能超过 " + maximumLength + " 个字符");
        }
        return value;
    }
}
