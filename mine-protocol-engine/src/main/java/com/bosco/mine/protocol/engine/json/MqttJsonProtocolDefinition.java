package com.bosco.mine.protocol.engine.json;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 描述一个可以编译为执行计划的 MQTT JSON 协议版本。
 *
 * @param protocolKey 稳定协议编码
 * @param version 单调递增版本号
 * @param metadata 统一消息元数据所在路径
 * @param properties 当前物模型属性集合
 * @param fields 厂商字段到物模型属性的映射
 */
public record MqttJsonProtocolDefinition(
        String protocolKey,
        int version,
        MetadataPaths metadata,
        List<PropertyDefinition> properties,
        List<FieldMapping> fields
) {

    /**
     * 对输入集合进行防御性复制，避免草稿在编译期间被修改。
     */
    public MqttJsonProtocolDefinition {
        properties = properties == null ? List.of() : List.copyOf(properties);
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    /**
     * MQTT JSON 中统一消息元数据的受限点路径。
     *
     * @param productKeyPath 产品编码路径
     * @param deviceCodePath 设备编码路径
     * @param bootIdPath 启动批次路径，可为空
     * @param sequencePath 设备序号路径，可为空
     * @param occurredAtPath 发生时间路径
     * @param replayedPath 补传标记路径
     */
    public record MetadataPaths(
            String productKeyPath,
            String deviceCodePath,
            String bootIdPath,
            String sequencePath,
            String occurredAtPath,
            String replayedPath
    ) {
    }

    /**
     * 当前配置包引用的物模型属性。
     *
     * @param identifier 稳定属性标识
     * @param dataType 属性数据类型
     */
    public record PropertyDefinition(String identifier, ValueType dataType) {
    }

    /**
     * 一个厂商字段的解析与映射规则。
     *
     * @param sourcePath 厂商 JSON 点路径
     * @param sourceType 原始字段类型
     * @param targetIdentifier 目标物模型属性
     * @param transform 受控转换类型
     * @param scaleFactor 缩放系数
     * @param offsetValue 偏移量
     * @param enumMapping 有限枚举码表
     * @param requiredField 是否必填
     */
    public record FieldMapping(
            String sourcePath,
            ValueType sourceType,
            String targetIdentifier,
            FieldTransform transform,
            BigDecimal scaleFactor,
            BigDecimal offsetValue,
            Map<String, Object> enumMapping,
            boolean requiredField
    ) {

        /**
         * 复制枚举映射，保证已编译配置不受外部修改影响。
         */
        public FieldMapping {
            enumMapping = enumMapping == null ? Map.of() : Map.copyOf(enumMapping);
        }
    }

    /**
     * 第一批协议引擎支持的稳定值类型。
     */
    public enum ValueType {
        /** 64 位以内整数。 */
        INTEGER,
        /** 任意精度十进制数。 */
        DECIMAL,
        /** 布尔值。 */
        BOOLEAN,
        /** 文本。 */
        STRING,
        /** 有限枚举。 */
        ENUM,
        /** ISO-8601 时间。 */
        DATETIME,
        /** 地理坐标对象，预留给后续策略。 */
        GEO_POINT
    }
}
