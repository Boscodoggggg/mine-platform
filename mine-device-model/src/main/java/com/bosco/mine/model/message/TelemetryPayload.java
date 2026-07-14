package com.bosco.mine.model.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Map;
import java.util.Objects;

/**
 * 表示已经映射到物模型属性标识的遥测值集合。
 *
 * @param values 属性标识到标准值的映射
 */
public record TelemetryPayload(Map<String, Object> values) {

    /**
     * 创建遥测载荷并与调用方的可变映射隔离。
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public TelemetryPayload {
        values = Map.copyOf(Objects.requireNonNull(values, "遥测属性不能为空"));
    }

    /**
     * 以普通 JSON 对象形式输出遥测属性。
     *
     * @return 不可修改的遥测属性
     */
    @Override
    @JsonValue
    public Map<String, Object> values() {
        return values;
    }
}
