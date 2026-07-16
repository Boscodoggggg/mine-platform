package com.bosco.mine.gateway.normalize;

import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.gateway.configuration.InvalidConfigurationSnapshotException;
import com.bosco.mine.gateway.configuration.ProtocolSnapshotRegistry;
import com.bosco.mine.gateway.mqtt.DeviceMessageConsumer;
import com.bosco.mine.gateway.session.DeviceSessionRegistry;
import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.MessageType;
import com.bosco.mine.model.message.TelemetryPayload;
import com.bosco.mine.model.message.UnifiedDeviceMessage;
import com.bosco.mine.protocol.engine.json.MqttJsonExecutionPlan;
import com.bosco.mine.protocol.engine.json.ProtocolExecutionResult;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 把首个 MQTT JSON 适配器输入转换为平台统一设备消息。
 */
public final class DeviceMessageNormalizer implements DeviceMessageConsumer.Normalizer {

    private static final MessageType EVENT_TYPE = MessageType.of("telemetry.normalized");
    private static final String PRODUCER = "mine-gateway";
    private static final TypeReference<Map<String, Object>> PROPERTY_TYPE = new TypeReference<>() { };
    private static final Pattern TOPIC_SEGMENT = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final DeviceSessionRegistry registry;
    private final ProtocolSnapshotRegistry protocolRegistry;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final int maxPayloadBytes;
    private final JsonMapper jsonMapper;

    /**
     * 创建 MQTT JSON 标准化器。
     *
     * @param registry 可信设备身份快照
     * @param idGenerator UUIDv7 生成器
     * @param clock 平台接收时钟
     * @param maxPayloadBytes 单条原始报文最大字节数
     */
    public DeviceMessageNormalizer(
            DeviceSessionRegistry registry,
            IdGenerator idGenerator,
            Clock clock,
            int maxPayloadBytes
    ) {
        this(registry, new ProtocolSnapshotRegistry(false), idGenerator, clock, maxPayloadBytes);
    }

    /**
     * 创建支持动态协议快照的 MQTT JSON 标准化器。
     *
     * @param registry 可信设备身份快照
     * @param protocolRegistry 动态协议执行计划注册表
     * @param idGenerator UUIDv7 生成器
     * @param clock 平台接收时钟
     * @param maxPayloadBytes 单条原始报文最大字节数
     */
    public DeviceMessageNormalizer(
            DeviceSessionRegistry registry,
            ProtocolSnapshotRegistry protocolRegistry,
            IdGenerator idGenerator,
            Clock clock,
            int maxPayloadBytes
    ) {
        this.registry = Objects.requireNonNull(registry, "registry 不能为空");
        this.protocolRegistry = Objects.requireNonNull(protocolRegistry, "protocolRegistry 不能为空");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        if (maxPayloadBytes < 1) throw new IllegalArgumentException("maxPayloadBytes 必须大于 0");
        this.maxPayloadBytes = maxPayloadBytes;
        this.jsonMapper = JsonMapper.builder().build();
    }

    /**
     * 校验 routing key、可信身份和 JSON 结构，并生成内部事件。
     *
     * @param routingKey RabbitMQ MQTT 插件转换后的 routing key
     * @param body 原始 UTF-8 JSON 字节
     * @return 标准化遥测事件
     */
    @Override
    public MessageEnvelope<UnifiedDeviceMessage> normalize(String routingKey, byte[] body) {
        Objects.requireNonNull(body, "body 不能为空");
        if (body.length > maxPayloadBytes) {
            throw new InvalidDeviceMessageException("原始报文超过允许的 " + maxPayloadBytes + " 字节");
        }
        TopicIdentity topic = TopicIdentity.parse(routingKey);
        DeviceSessionRegistry.DeviceIdentity identity = registry.find(topic.productKey(), topic.clientId())
                .orElseThrow(() -> new InvalidDeviceMessageException("MQTT 客户端身份未登记"));
        var activeProtocol = protocolRegistry.find(identity.productKey());
        if (activeProtocol.isPresent()) {
            return normalizeDynamic(identity, activeProtocol.orElseThrow(), body);
        }
        if (protocolRegistry.required()) {
            try {
                protocolRegistry.require(identity.productKey());
            } catch (InvalidConfigurationSnapshotException exception) {
                throw new InvalidDeviceMessageException(exception.getMessage(), exception);
            }
        }
        JsonNode root = readRoot(body);
        requireEqual(text(root, "productKey"), identity.productKey(), "productKey 与凭据身份不一致");
        requireEqual(text(root, "deviceCode"), identity.deviceCode(), "deviceCode 与可信设备映射不一致");

        Instant now = Instant.now(clock);
        Instant occurredAt = instant(root, "occurredAt");
        UUID messageId = idGenerator.next();
        UnifiedDeviceMessage message = new UnifiedDeviceMessage(
                messageId,
                UnifiedDeviceMessage.MessageKind.TELEMETRY,
                identity.productKey(),
                identity.deviceId(),
                identity.protocolVersion(),
                optionalText(root, "bootId"),
                optionalLong(root, "sequence"),
                occurredAt,
                now,
                booleanValue(root, "replayed"),
                new TelemetryPayload(properties(root)),
                UnifiedDeviceMessage.Quality.GOOD,
                null
        );
        return new MessageEnvelope<>(
                messageId,
                EVENT_TYPE,
                1,
                occurredAt,
                now,
                PRODUCER,
                messageId.toString(),
                null,
                identity.deviceId(),
                identity.tenantScope(),
                message
        );
    }

    private MessageEnvelope<UnifiedDeviceMessage> normalizeDynamic(
            DeviceSessionRegistry.DeviceIdentity identity,
            ProtocolSnapshotRegistry.ActiveProtocol active,
            byte[] body
    ) {
        ProtocolExecutionResult result = active.plan().execute(
                body,
                new MqttJsonExecutionPlan.TrustedIdentity(
                        identity.productKey(), identity.deviceCode())
        );
        if (!result.success()) {
            throw new InvalidDeviceMessageException(
                    "动态协议解析失败[%s]: %s".formatted(result.errorCode(), result.errorMessage()));
        }
        Instant now = Instant.now(clock);
        UUID messageId = idGenerator.next();
        UnifiedDeviceMessage message = new UnifiedDeviceMessage(
                messageId,
                UnifiedDeviceMessage.MessageKind.TELEMETRY,
                identity.productKey(),
                identity.deviceId(),
                active.protocolVersion(),
                result.bootId(),
                result.sequence(),
                result.occurredAt(),
                now,
                result.replayed(),
                new TelemetryPayload(result.properties()),
                UnifiedDeviceMessage.Quality.GOOD,
                null
        );
        return new MessageEnvelope<>(
                messageId,
                EVENT_TYPE,
                1,
                result.occurredAt(),
                now,
                PRODUCER,
                messageId.toString(),
                active.releaseId().toString(),
                identity.deviceId(),
                identity.tenantScope(),
                message
        );
    }

    private JsonNode readRoot(byte[] body) {
        try {
            JsonNode root = jsonMapper.readTree(body);
            if (root == null || !root.isObject()) {
                throw new InvalidDeviceMessageException("MQTT JSON 根节点必须是对象");
            }
            return root;
        } catch (JacksonException exception) {
            throw new InvalidDeviceMessageException("MQTT JSON 无法解析", exception);
        }
    }

    private Map<String, Object> properties(JsonNode root) {
        JsonNode properties = root.get("properties");
        if (properties == null || !properties.isObject()) {
            throw new InvalidDeviceMessageException("properties 必须是对象");
        }
        try {
            return jsonMapper.convertValue(properties, PROPERTY_TYPE);
        } catch (IllegalArgumentException exception) {
            throw new InvalidDeviceMessageException("properties 包含不支持的值", exception);
        }
    }

    private String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new InvalidDeviceMessageException(field + " 必须是非空字符串");
        }
        return value.asString();
    }

    private String optionalText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isString() || value.asString().isBlank()) {
            throw new InvalidDeviceMessageException(field + " 必须是非空字符串或 null");
        }
        return value.asString();
    }

    private Long optionalLong(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new InvalidDeviceMessageException(field + " 必须是长整数或 null");
        }
        return value.asLong();
    }

    private boolean booleanValue(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isBoolean()) {
            throw new InvalidDeviceMessageException(field + " 必须是布尔值");
        }
        return value.asBoolean();
    }

    private Instant instant(JsonNode root, String field) {
        try {
            return Instant.parse(text(root, field));
        } catch (RuntimeException exception) {
            throw new InvalidDeviceMessageException(field + " 必须是 ISO-8601 时间", exception);
        }
    }

    private void requireEqual(String actual, String expected, String reason) {
        if (!expected.equals(actual)) throw new InvalidDeviceMessageException(reason);
    }

    private record TopicIdentity(String productKey, String clientId) {
        private static TopicIdentity parse(String routingKey) {
            if (routingKey == null) throw new InvalidDeviceMessageException("routing key 不能为空");
            String[] segments = routingKey.split("\\.", -1);
            if (segments.length != 6
                    || !"mine".equals(segments[0])
                    || !"v1".equals(segments[1])
                    || !"up".equals(segments[4])
                    || !"telemetry".equals(segments[5])
                    || !TOPIC_SEGMENT.matcher(segments[2]).matches()
                    || !TOPIC_SEGMENT.matcher(segments[3]).matches()) {
                throw new InvalidDeviceMessageException("routing key 不符合遥测主题规范");
            }
            return new TopicIdentity(segments[2], segments[3]);
        }
    }
}
