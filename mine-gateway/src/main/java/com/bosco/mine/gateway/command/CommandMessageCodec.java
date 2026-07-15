package com.bosco.mine.gateway.command;

import com.bosco.mine.gateway.session.DeviceSessionRegistry;
import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.command.CommandDispatchPayload;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 负责平台命令信封与设备 MQTT JSON ACK 的受限解码。
 */
public final class CommandMessageCodec implements CommandDispatchConsumer.Decoder, CommandAckConsumer.Decoder {
    private static final String DISPATCH_EVENT = "command.dispatch.requested";
    private static final Set<String> TERMINAL_ACK_STATUSES = Set.of("SUCCEEDED", "REFUSED", "FAILED");

    private final DeviceSessionRegistry registry;
    private final int maxPayloadBytes;
    private final JsonMapper jsonMapper;

    /**
     * 创建命令消息编解码器。
     *
     * @param registry 可信设备身份注册表
     * @param maxPayloadBytes 单条消息最大字节数
     */
    public CommandMessageCodec(DeviceSessionRegistry registry, int maxPayloadBytes) {
        this.registry = Objects.requireNonNull(registry, "设备身份注册表不能为空");
        if (maxPayloadBytes < 1) {
            throw new IllegalArgumentException("消息最大字节数必须大于 0");
        }
        this.maxPayloadBytes = maxPayloadBytes;
        this.jsonMapper = JsonMapper.builder().findAndAddModules().build();
    }

    /**
     * 解码平台命令信封并校验路由相关字段一致性。
     *
     * @param body JSON 字节
     * @return 命令信封
     */
    @Override
    public MessageEnvelope<CommandDispatchPayload> decode(byte[] body) {
        requirePayloadSize(body);
        try {
            MessageEnvelope<CommandDispatchPayload> envelope = jsonMapper.readValue(
                    body, new TypeReference<>() { }
            );
            if (!DISPATCH_EVENT.equals(envelope.eventType().value()) || envelope.schemaVersion() != 1) {
                throw new InvalidCommandMessageException("命令事件类型或结构版本不受支持");
            }
            CommandDispatchPayload payload = envelope.payload();
            if (!payload.commandId().toString().equals(envelope.correlationId())
                    || !payload.deviceId().toString().equals(envelope.partitionKey())) {
                throw new InvalidCommandMessageException("命令信封关联标识与载荷不一致");
            }
            return envelope;
        } catch (InvalidCommandMessageException exception) {
            throw exception;
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new InvalidCommandMessageException("命令信封 JSON 无法解析", exception);
        }
    }

    /**
     * 解码设备 ACK，并以 MQTT 主题身份覆盖报文内可能伪造的设备身份。
     *
     * @param routingKey MQTT 转换后的路由键
     * @param body ACK JSON 字节
     * @return 已绑定可信设备身份的 ACK
     */
    @Override
    public CommandAckConsumer.DecodedAck decode(String routingKey, byte[] body) {
        requirePayloadSize(body);
        TopicIdentity topic = TopicIdentity.parse(routingKey);
        DeviceSessionRegistry.DeviceIdentity identity = registry.find(topic.productKey(), topic.clientId())
                .orElseThrow(() -> new InvalidCommandMessageException("命令 ACK 的 MQTT 设备身份未登记"));
        try {
            JsonNode root = jsonMapper.readTree(body);
            if (root == null || !root.isObject()) {
                throw new InvalidCommandMessageException("命令 ACK 根节点必须是对象");
            }
            UUID commandId = UUID.fromString(requiredText(root, "commandId"));
            String status = requiredText(root, "status");
            if (!TERMINAL_ACK_STATUSES.contains(status)) {
                throw new InvalidCommandMessageException("设备 ACK 状态不受支持: " + status);
            }
            Instant occurredAt = Instant.parse(requiredText(root, "occurredAt"));
            Map<String, Object> reply = objectMap(root.get("result"));
            String traceId = optionalText(root, "traceId");
            if (traceId == null) {
                traceId = "command-ack-" + commandId;
            }
            return new CommandAckConsumer.DecodedAck(
                    commandId, UUID.fromString(identity.deviceId()), status, occurredAt,
                    reply, optionalText(root, "errorCode"), traceId, identity.tenantScope()
            );
        } catch (InvalidCommandMessageException exception) {
            throw exception;
        } catch (JacksonException | DateTimeParseException | IllegalArgumentException exception) {
            throw new InvalidCommandMessageException("设备命令 ACK JSON 无法解析", exception);
        }
    }

    /**
     * 编码设备能够直接处理的 MQTT 命令 JSON。
     *
     * @param envelope 平台命令信封
     * @return MQTT JSON 字节
     */
    public byte[] encodeDownlink(MessageEnvelope<CommandDispatchPayload> envelope) {
        CommandDispatchPayload payload = envelope.payload();
        try {
            return jsonMapper.writeValueAsBytes(Map.of(
                    "commandId", payload.commandId().toString(),
                    "command", payload.templateKey(),
                    "templateVersion", payload.templateVersion(),
                    "parameters", payload.parameters(),
                    "expiresAt", payload.expiresAt().toString(),
                    "traceId", envelope.traceId()
            ));
        } catch (JacksonException exception) {
            throw new InvalidCommandMessageException("设备命令 JSON 无法编码", exception);
        }
    }

    private void requirePayloadSize(byte[] body) {
        if (body == null || body.length == 0 || body.length > maxPayloadBytes) {
            throw new InvalidCommandMessageException("命令消息大小不合法");
        }
    }

    private String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null) {
            throw new InvalidCommandMessageException("命令 ACK 字段缺失: " + field);
        }
        return value;
    }

    private String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isString() || value.asString().isBlank()) {
            throw new InvalidCommandMessageException("命令 ACK 字段必须为非空字符串: " + field);
        }
        return value.asString();
    }

    private Map<String, Object> objectMap(JsonNode value) throws JacksonException {
        if (value == null || value.isNull()) return Map.of();
        if (!value.isObject()) {
            throw new InvalidCommandMessageException("命令 ACK result 必须是对象");
        }
        return jsonMapper.convertValue(value, new TypeReference<>() { });
    }

    private record TopicIdentity(String productKey, String clientId) {
        private static TopicIdentity parse(String routingKey) {
            if (routingKey == null) {
                throw new InvalidCommandMessageException("命令 ACK routing key 不能为空");
            }
            String[] segments = routingKey.split("\\.", -1);
            if (segments.length != 6
                    || !"mine".equals(segments[0])
                    || !"v1".equals(segments[1])
                    || !"up".equals(segments[4])
                    || !"command-ack".equals(segments[5])) {
                throw new InvalidCommandMessageException("routing key 不符合命令 ACK 主题规范");
            }
            return new TopicIdentity(segments[2], segments[3]);
        }
    }
}
