package com.bosco.mine.messaging;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Objects;

/**
 * 表示可扩展的内部事件类型，例如 {@code telemetry.normalized}。
 *
 * @param value 事件类型字符串
 */
public record MessageType(String value) {

    /**
     * 创建并校验事件类型。
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public MessageType {
        Objects.requireNonNull(value, "eventType 不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException("eventType 不能为空白字符串");
        }
    }

    /**
     * 从稳定字符串创建事件类型。
     *
     * @param value 事件类型字符串
     * @return 事件类型
     */
    public static MessageType of(String value) {
        return new MessageType(value);
    }

    /**
     * 将事件类型序列化为单个 JSON 字符串。
     *
     * @return 事件类型字符串
     */
    @Override
    @JsonValue
    public String value() {
        return value;
    }
}
