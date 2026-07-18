package com.bosco.mine.gateway.location;

import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.gateway.normalize.InvalidDeviceMessageException;
import com.bosco.mine.gateway.session.DeviceSessionRegistry;
import com.bosco.mine.model.location.LocationObservation;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 把独立 MQTT 定位主题的固定 JSON v1 报文转换为可追溯观测。
 *
 * <p>该边界只信任已激活凭据快照中的设备标识，不根据原始载荷决定矿区、
 * 绑定对象或统一坐标。定位协议保持固定字段，不进入动态物模型属性映射。</p>
 */
public final class LocationMessageNormalizer implements LocationMessageConsumer.Normalizer {

    private static final int SCHEMA_VERSION = 1;
    private static final Pattern TOPIC_SEGMENT = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "schemaVersion", "productKey", "deviceCode", "tagCode", "coordinateSystemCode",
            "x", "y", "z", "speedMetersPerSecond", "headingDegrees", "accuracyMeters",
            "bootId", "sequence", "occurredAt");

    private final DeviceSessionRegistry registry;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final int maxPayloadBytes;
    private final JsonMapper jsonMapper;

    /**
     * 创建定位报文标准化器。
     *
     * @param registry 已激活的可信设备身份快照
     * @param idGenerator UUIDv7 观测标识生成器
     * @param clock Gateway 接收时钟
     * @param maxPayloadBytes 单条原始定位报文最大字节数
     */
    public LocationMessageNormalizer(
            DeviceSessionRegistry registry,
            IdGenerator idGenerator,
            Clock clock,
            int maxPayloadBytes
    ) {
        this.registry = Objects.requireNonNull(registry, "registry 不能为空");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        if (maxPayloadBytes < 1) {
            throw new IllegalArgumentException("maxPayloadBytes 必须大于 0");
        }
        this.maxPayloadBytes = maxPayloadBytes;
        this.jsonMapper = JsonMapper.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
    }

    /**
     * 校验主题身份、载荷身份和定位数值，并生成强类型观测。
     *
     * @param routingKey RabbitMQ MQTT 插件转换后的路由键
     * @param body 原始 UTF-8 JSON 字节
     * @return 只包含可信来源和原始坐标的定位观测
     */
    @Override
    public LocationObservation normalize(String routingKey, byte[] body) {
        if (body == null) {
            throw new InvalidDeviceMessageException("定位报文不能为空");
        }
        if (body.length > maxPayloadBytes) {
            throw new InvalidDeviceMessageException(
                    "原始定位报文超过允许的 " + maxPayloadBytes + " 字节");
        }
        TopicIdentity topic = TopicIdentity.parse(routingKey);
        DeviceSessionRegistry.DeviceIdentity identity = registry
                .find(topic.productKey(), topic.clientId())
                .orElseThrow(() -> new InvalidDeviceMessageException("MQTT 定位客户端身份未登记"));
        JsonNode root = readRoot(body);
        rejectUnknownFields(root);
        if (requiredInt(root, "schemaVersion") != SCHEMA_VERSION) {
            throw new InvalidDeviceMessageException("schemaVersion 只支持 1");
        }
        requireEqual(text(root, "productKey"), identity.productKey(),
                "productKey 与凭据身份不一致");
        requireEqual(text(root, "deviceCode"), identity.deviceCode(),
                "deviceCode 与可信设备映射不一致");

        UUID observationId = idGenerator.next();
        if (observationId == null || observationId.version() != 7) {
            throw new IllegalStateException("观测标识生成器未返回 UUIDv7");
        }
        try {
            return new LocationObservation(
                    observationId,
                    UUID.fromString(identity.deviceId()),
                    text(root, "tagCode"),
                    text(root, "coordinateSystemCode"),
                    finiteNumber(root, "x", "X 坐标"),
                    finiteNumber(root, "y", "Y 坐标"),
                    optionalFiniteNumber(root, "z", "Z 坐标"),
                    optionalFiniteNumber(root, "speedMetersPerSecond", "速度"),
                    optionalFiniteNumber(root, "headingDegrees", "航向"),
                    optionalFiniteNumber(root, "accuracyMeters", "精度"),
                    text(root, "bootId"),
                    requiredLong(root, "sequence"),
                    instant(root, "occurredAt"),
                    Instant.now(clock)
            );
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new InvalidDeviceMessageException(
                    "定位观测字段不合法: " + exception.getMessage(), exception);
        }
    }

    private JsonNode readRoot(byte[] body) {
        try {
            JsonNode root = jsonMapper.readTree(body);
            if (root == null || !root.isObject()) {
                throw new InvalidDeviceMessageException("定位 JSON 根节点必须是对象");
            }
            return root;
        } catch (JacksonException exception) {
            throw new InvalidDeviceMessageException("定位 JSON 无法解析", exception);
        }
    }

    private void rejectUnknownFields(JsonNode root) {
        root.propertyNames().stream()
                .filter(field -> !ALLOWED_FIELDS.contains(field))
                .findFirst()
                .ifPresent(field -> {
                    throw new InvalidDeviceMessageException("定位 JSON 包含未知字段: " + field);
                });
    }

    private String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new InvalidDeviceMessageException(field + " 必须是非空字符串");
        }
        return value.asString();
    }

    private int requiredInt(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new InvalidDeviceMessageException(field + " 必须是整数");
        }
        return value.asInt();
    }

    private long requiredLong(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new InvalidDeviceMessageException(field + " 必须是长整数");
        }
        return value.asLong();
    }

    private double finiteNumber(JsonNode root, String field, String displayName) {
        JsonNode value = root.get(field);
        if (value == null || !value.isNumber() || !Double.isFinite(value.asDouble())) {
            throw new InvalidDeviceMessageException(displayName + "必须是有限数字");
        }
        return value.asDouble();
    }

    private Double optionalFiniteNumber(JsonNode root, String field, String displayName) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return finiteNumber(root, field, displayName);
    }

    private Instant instant(JsonNode root, String field) {
        try {
            return Instant.parse(text(root, field));
        } catch (RuntimeException exception) {
            if (exception instanceof InvalidDeviceMessageException invalid) {
                throw invalid;
            }
            throw new InvalidDeviceMessageException(field + " 必须是 ISO-8601 时间", exception);
        }
    }

    private void requireEqual(String actual, String expected, String reason) {
        if (!expected.equals(actual)) {
            throw new InvalidDeviceMessageException(reason);
        }
    }

    private record TopicIdentity(String productKey, String clientId) {
        private static TopicIdentity parse(String routingKey) {
            if (routingKey == null) {
                throw new InvalidDeviceMessageException("定位 routing key 不能为空");
            }
            String[] segments = routingKey.split("\\.", -1);
            if (segments.length != 6
                    || !"mine".equals(segments[0])
                    || !"v1".equals(segments[1])
                    || !"up".equals(segments[4])
                    || !"location".equals(segments[5])
                    || !TOPIC_SEGMENT.matcher(segments[2]).matches()
                    || !TOPIC_SEGMENT.matcher(segments[3]).matches()) {
                throw new InvalidDeviceMessageException("定位 routing key 不符合主题规范");
            }
            return new TopicIdentity(segments[2], segments[3]);
        }
    }
}
