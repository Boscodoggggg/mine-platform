package com.bosco.mine.protocol.engine.json;

import com.bosco.mine.protocol.engine.ProtocolValidationException;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 校验 MQTT JSON 定义并编译为不可变执行计划。
 */
public final class MqttJsonProtocolCompiler {

    private static final int MAX_FIELDS = 500;
    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9_-]{2,63}$");
    private static final Pattern IDENTIFIER = Pattern.compile("^[a-z][A-Za-z0-9_]{1,127}$");
    private static final Pattern PATH = Pattern.compile(
            "^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*){0,7}$");

    /**
     * 编译并冻结一份协议定义。
     *
     * @param definition 待发布定义
     * @return 不可变执行计划
     * @throws ProtocolValidationException 定义不安全或内部不兼容时抛出
     */
    public MqttJsonExecutionPlan compile(MqttJsonProtocolDefinition definition) {
        if (definition == null || definition.protocolKey() == null
                || !KEY.matcher(definition.protocolKey()).matches() || definition.version() < 1) {
            fail("PROTOCOL-DEFINITION-INVALID", "协议编码或版本不合法");
        }
        validateMetadata(definition.metadata());
        if (definition.properties().isEmpty() || definition.properties().size() > MAX_FIELDS) {
            fail("PROTOCOL-PROPERTY-LIMIT", "物模型属性数量必须在 1 到 500 之间");
        }
        if (definition.fields().isEmpty() || definition.fields().size() > MAX_FIELDS) {
            fail("PROTOCOL-FIELD-LIMIT", "协议字段数量必须在 1 到 500 之间");
        }

        Map<String, MqttJsonProtocolDefinition.ValueType> properties = new HashMap<>();
        for (MqttJsonProtocolDefinition.PropertyDefinition property : definition.properties()) {
            if (property == null || property.identifier() == null
                    || !IDENTIFIER.matcher(property.identifier()).matches() || property.dataType() == null) {
                fail("PROTOCOL-PROPERTY-INVALID", "物模型属性定义不合法");
            }
            if (properties.putIfAbsent(property.identifier(), property.dataType()) != null) {
                fail("PROTOCOL-PROPERTY-DUPLICATED", "物模型属性重复：" + property.identifier());
            }
        }

        Set<String> sourcePaths = new HashSet<>();
        Set<String> targets = new HashSet<>();
        for (MqttJsonProtocolDefinition.FieldMapping field : definition.fields()) {
            validatePath(field == null ? null : field.sourcePath());
            if (field.sourceType() == null || field.targetIdentifier() == null || field.transform() == null) {
                fail("PROTOCOL-FIELD-INVALID", "协议字段类型、目标或转换不能为空");
            }
            if (!sourcePaths.add(field.sourcePath())) {
                fail("PROTOCOL-SOURCE-DUPLICATED", "源字段重复：" + field.sourcePath());
            }
            if (!targets.add(field.targetIdentifier())) {
                fail("PROTOCOL-TARGET-DUPLICATED", "目标字段重复：" + field.targetIdentifier());
            }
            MqttJsonProtocolDefinition.ValueType targetType = properties.get(field.targetIdentifier());
            if (targetType == null) {
                fail("PROTOCOL-TARGET-NOT-FOUND", "映射目标不在物模型中：" + field.targetIdentifier());
            }
            validateTransform(field, targetType);
        }
        return new MqttJsonExecutionPlan(definition, Map.copyOf(properties));
    }

    private void validateMetadata(MqttJsonProtocolDefinition.MetadataPaths metadata) {
        if (metadata == null || metadata.productKeyPath() == null || metadata.deviceCodePath() == null
                || metadata.occurredAtPath() == null || metadata.replayedPath() == null) {
            fail("PROTOCOL-METADATA-INVALID", "产品、设备、发生时间和补传标记路径不能为空");
        }
        validatePath(metadata.productKeyPath());
        validatePath(metadata.deviceCodePath());
        validateOptionalPath(metadata.bootIdPath());
        validateOptionalPath(metadata.sequencePath());
        validatePath(metadata.occurredAtPath());
        validatePath(metadata.replayedPath());
    }

    private void validateOptionalPath(String path) {
        if (path != null) validatePath(path);
    }

    private void validatePath(String path) {
        if (path == null || !PATH.matcher(path).matches()) {
            fail("PROTOCOL-PATH-INVALID", "JSON 路径不合法或超过八层：" + path);
        }
    }

    private void validateTransform(
            MqttJsonProtocolDefinition.FieldMapping field,
            MqttJsonProtocolDefinition.ValueType targetType
    ) {
        boolean sourceNumeric = numeric(field.sourceType());
        boolean targetNumeric = numeric(targetType);
        switch (field.transform()) {
            case SCALE -> requireNumeric(field, sourceNumeric, targetNumeric, field.scaleFactor() != null);
            case OFFSET -> requireNumeric(field, sourceNumeric, targetNumeric, field.offsetValue() != null);
            case SCALE_OFFSET -> requireNumeric(
                    field, sourceNumeric, targetNumeric,
                    field.scaleFactor() != null && field.offsetValue() != null);
            case ENUM -> {
                boolean sourceAllowed = field.sourceType() == MqttJsonProtocolDefinition.ValueType.STRING
                        || field.sourceType() == MqttJsonProtocolDefinition.ValueType.INTEGER;
                boolean targetAllowed = targetType == MqttJsonProtocolDefinition.ValueType.ENUM
                        || targetType == MqttJsonProtocolDefinition.ValueType.STRING;
                if (!sourceAllowed || !targetAllowed || field.enumMapping().isEmpty()) {
                    fail("PROTOCOL-TRANSFORM-INCOMPATIBLE", "枚举转换类型或码表不合法：" + field.sourcePath());
                }
            }
            case NONE -> {
                if (field.sourceType() != targetType && !(sourceNumeric && targetNumeric)) {
                    fail("PROTOCOL-TRANSFORM-INCOMPATIBLE", "源字段与目标字段类型不兼容：" + field.sourcePath());
                }
            }
        }
    }

    private void requireNumeric(
            MqttJsonProtocolDefinition.FieldMapping field,
            boolean sourceNumeric,
            boolean targetNumeric,
            boolean parametersPresent
    ) {
        if (!sourceNumeric || !targetNumeric || !parametersPresent) {
            fail("PROTOCOL-TRANSFORM-INCOMPATIBLE", "数值转换类型或参数不合法：" + field.sourcePath());
        }
    }

    private boolean numeric(MqttJsonProtocolDefinition.ValueType type) {
        return type == MqttJsonProtocolDefinition.ValueType.INTEGER
                || type == MqttJsonProtocolDefinition.ValueType.DECIMAL;
    }

    private void fail(String code, String message) {
        throw new ProtocolValidationException(code, message);
    }
}
