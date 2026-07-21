package com.bosco.mine.protocol.engine.json;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 已完成发布校验、可以直接处理 MQTT JSON 报文的不可变执行计划。
 */
public final class MqttJsonExecutionPlan {

    private final MqttJsonProtocolDefinition definition;
    private final Map<String, MqttJsonProtocolDefinition.ValueType> propertyTypes;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    MqttJsonExecutionPlan(
            MqttJsonProtocolDefinition definition,
            Map<String, MqttJsonProtocolDefinition.ValueType> propertyTypes
    ) {
        this.definition = definition;
        this.propertyTypes = Map.copyOf(propertyTypes);
    }

    /** 返回协议编码。 */
    public String protocolKey() {
        return definition.protocolKey();
    }

    /** 返回协议版本。 */
    public int version() {
        return definition.version();
    }

    /** 返回不可变字段映射集合。 */
    public List<MqttJsonProtocolDefinition.FieldMapping> fields() {
        return definition.fields();
    }

    /**
     * 使用可信设备身份执行一条 MQTT JSON 报文。
     *
     * @param payload 原始 UTF-8 JSON
     * @param trustedIdentity 从 MQTT 凭据解析出的可信身份
     * @return 成功结果或结构化失败结果
     */
    public ProtocolExecutionResult execute(byte[] payload, TrustedIdentity trustedIdentity) {
        Objects.requireNonNull(payload, "payload 不能为空");
        Objects.requireNonNull(trustedIdentity, "可信身份不能为空");
        try {
            JsonNode root = jsonMapper.readTree(payload);
            if (root == null || !root.isObject()) {
                return failure("PROTOCOL-JSON-INVALID", "报文根节点必须是 JSON 对象");
            }
            MqttJsonProtocolDefinition.MetadataPaths metadata = definition.metadata();
            String productKey = requiredText(root, metadata.productKeyPath());
            String deviceCode = requiredText(root, metadata.deviceCodePath());
            if (!trustedIdentity.productKey().equals(productKey)
                    || !trustedIdentity.deviceCode().equals(deviceCode)) {
                return failure("PROTOCOL-IDENTITY-MISMATCH", "报文身份与 MQTT 可信身份不一致");
            }
            String bootId = optionalText(root, metadata.bootIdPath());
            Long sequence = optionalLong(root, metadata.sequencePath());
            Instant occurredAt = Instant.parse(requiredText(root, metadata.occurredAtPath()));
            boolean replayed = requiredBoolean(root, metadata.replayedPath());

            Map<String, Object> properties = new LinkedHashMap<>();
            for (MqttJsonProtocolDefinition.FieldMapping field : definition.fields()) {
                JsonNode value = resolve(root, field.sourcePath());
                if (value == null || value.isNull()) {
                    if (field.requiredField()) {
                        return failure(
                                "PROTOCOL-REQUIRED-FIELD-MISSING", "缺少必填字段：" + field.sourcePath());
                    }
                    continue;
                }
                Object source = readValue(value, field.sourceType());
                Object transformed = transform(source, field);
                Object target = convertTarget(transformed, propertyTypes.get(field.targetIdentifier()));
                properties.put(field.targetIdentifier(), target);
            }
            return ProtocolExecutionResult.succeeded(
                    productKey, deviceCode, bootId, sequence, occurredAt, replayed, properties);
        } catch (JacksonException exception) {
            return failure("PROTOCOL-JSON-INVALID", "报文不是合法 JSON");
        } catch (DateTimeParseException exception) {
            return failure("PROTOCOL-METADATA-INVALID", "发生时间不是合法 ISO-8601 时间");
        } catch (ExecutionFailure exception) {
            return failure(exception.code, exception.getMessage());
        } catch (RuntimeException exception) {
            return failure("PROTOCOL-EXECUTION-FAILED", "协议执行失败");
        }
    }

    private JsonNode resolve(JsonNode root, String path) {
        if (path == null) return null;
        JsonNode current = root;
        for (String segment : path.split("\\.")) {
            if (current == null || !current.isObject()) return null;
            current = current.get(segment);
        }
        return current;
    }

    private String requiredText(JsonNode root, String path) {
        JsonNode value = resolve(root, path);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw error("PROTOCOL-METADATA-INVALID", "元数据字段必须是非空文本：" + path);
        }
        return value.asString();
    }

    private String optionalText(JsonNode root, String path) {
        if (path == null) return null;
        JsonNode value = resolve(root, path);
        if (value == null || value.isNull()) return null;
        if (!value.isString() || value.asString().isBlank()) {
            throw error("PROTOCOL-METADATA-INVALID", "可选元数据字段类型不合法：" + path);
        }
        return value.asString();
    }

    private Long optionalLong(JsonNode root, String path) {
        if (path == null) return null;
        JsonNode value = resolve(root, path);
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw error("PROTOCOL-METADATA-INVALID", "消息序号必须是长整数：" + path);
        }
        return value.asLong();
    }

    private boolean requiredBoolean(JsonNode root, String path) {
        JsonNode value = resolve(root, path);
        if (value == null || !value.isBoolean()) {
            throw error("PROTOCOL-METADATA-INVALID", "补传标记必须是布尔值：" + path);
        }
        return value.asBoolean();
    }

    private Object readValue(JsonNode value, MqttJsonProtocolDefinition.ValueType type) {
        return switch (type) {
            case INTEGER -> {
                if (!value.isIntegralNumber() || !value.canConvertToLong()) {
                    throw error("PROTOCOL-SOURCE-TYPE-MISMATCH", "字段不是合法整数");
                }
                long number = value.asLong();
                if (number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE) yield (int) number;
                yield number;
            }
            case DECIMAL -> {
                if (!value.isNumber()) throw error("PROTOCOL-SOURCE-TYPE-MISMATCH", "字段不是合法数值");
                yield value.decimalValue();
            }
            case BOOLEAN -> {
                if (!value.isBoolean()) throw error("PROTOCOL-SOURCE-TYPE-MISMATCH", "字段不是布尔值");
                yield value.asBoolean();
            }
            case STRING, ENUM, DATETIME -> {
                if (!value.isString()) throw error("PROTOCOL-SOURCE-TYPE-MISMATCH", "字段不是文本");
                yield value.asString();
            }
            case GEO_POINT -> throw error("PROTOCOL-SOURCE-TYPE-MISMATCH", "第一批暂不支持坐标对象源字段");
        };
    }

    private Object transform(Object source, MqttJsonProtocolDefinition.FieldMapping field) {
        return switch (field.transform()) {
            case NONE -> source;
            case SCALE -> decimal(source).multiply(field.scaleFactor());
            case OFFSET -> decimal(source).add(field.offsetValue());
            case SCALE_OFFSET -> decimal(source).multiply(field.scaleFactor()).add(field.offsetValue());
            case ENUM -> {
                Object mapped = field.enumMapping().get(String.valueOf(source));
                if (mapped == null) throw error("PROTOCOL-TRANSFORM-FAILED", "枚举码未声明");
                yield mapped;
            }
        };
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number) return new BigDecimal(number.toString());
        throw error("PROTOCOL-TRANSFORM-FAILED", "数值转换输入不合法");
    }

    private Object convertTarget(Object value, MqttJsonProtocolDefinition.ValueType targetType) {
        return switch (targetType) {
            case INTEGER -> {
                BigDecimal decimal = decimal(value);
                try {
                    long number = decimal.longValueExact();
                    if (number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE) yield (int) number;
                    yield number;
                } catch (ArithmeticException exception) {
                    throw error("PROTOCOL-TRANSFORM-FAILED", "目标整数发生精度损失");
                }
            }
            case DECIMAL -> decimal(value);
            case BOOLEAN -> {
                if (!(value instanceof Boolean)) throw error("PROTOCOL-TRANSFORM-FAILED", "目标布尔类型不匹配");
                yield value;
            }
            case STRING, ENUM -> String.valueOf(value);
            case DATETIME -> {
                if (!(value instanceof String text)) throw error("PROTOCOL-TRANSFORM-FAILED", "目标时间类型不匹配");
                Instant.parse(text);
                yield text;
            }
            case GEO_POINT -> throw error("PROTOCOL-TRANSFORM-FAILED", "第一批暂不支持坐标对象转换");
        };
    }

    private ProtocolExecutionResult failure(String code, String message) {
        return ProtocolExecutionResult.failed(code, message);
    }

    private ExecutionFailure error(String code, String message) {
        return new ExecutionFailure(code, message);
    }

    /**
     * 来自设备凭据和 MQTT Topic 的可信身份。
     *
     * @param productKey 产品编码
     * @param deviceCode 业务设备编码
     */
    public record TrustedIdentity(String productKey, String deviceCode) {

        /** 校验可信身份不能为空。 */
        public TrustedIdentity {
            Objects.requireNonNull(productKey, "productKey 不能为空");
            Objects.requireNonNull(deviceCode, "deviceCode 不能为空");
        }
    }

    private static final class ExecutionFailure extends RuntimeException {
        private final String code;

        private ExecutionFailure(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
