package com.bosco.mine.server.bootstrap.websocket;

import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.bootstrap.websocket.RealtimeSessionRegistry.DeviceLatestSubscription;
import com.bosco.mine.server.bootstrap.websocket.RealtimeSessionRegistry.RealtimeLimitExceededException;
import com.bosco.mine.server.bootstrap.websocket.RealtimeSessionRegistry.RealtimeTransportException;
import com.bosco.mine.server.modules.device.dto.DeviceLatestResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 处理统一实时通道的鉴权、订阅、取消订阅、心跳和按需快照同步。
 */
public final class RealtimeWebSocketHandler extends TextWebSocketHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(RealtimeWebSocketHandler.class);
    private static final String TELEMETRY_PERMISSION = "device:telemetry:view";

    private final RealtimeAuthenticator authenticator;
    private final RealtimeSnapshotProvider snapshots;
    private final RealtimeSessionRegistry registry;
    private final RealtimeLimits limits;
    private final JsonMapper jsonMapper;

    /**
     * 创建实时 WebSocket 处理器。
     *
     * @param authenticator 令牌鉴权器
     * @param snapshots 最新值快照读取器
     * @param registry 会话注册表
     * @param limits 单连接资源限制
     */
    public RealtimeWebSocketHandler(
            RealtimeAuthenticator authenticator,
            RealtimeSnapshotProvider snapshots,
            RealtimeSessionRegistry registry,
            RealtimeLimits limits
    ) {
        this.authenticator = Objects.requireNonNull(authenticator, "实时鉴权器不能为空");
        this.snapshots = Objects.requireNonNull(snapshots, "快照读取器不能为空");
        this.registry = Objects.requireNonNull(registry, "会话注册表不能为空");
        this.limits = Objects.requireNonNull(limits, "实时限制不能为空");
        this.jsonMapper = JsonMapper.builder().findAndAddModules().build();
    }

    /**
     * 注册新连接并限制客户端文本帧大小。
     *
     * @param session 新 WebSocket 会话
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        session.setTextMessageSizeLimit(limits.maxMessageBytes());
        registry.register(session);
    }

    /**
     * 解析并执行客户端动作。所有业务错误通过 ERROR 帧返回，不依赖定时查询恢复。
     *
     * @param session WebSocket 会话
     * @param message 客户端文本消息
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String requestId = null;
        try {
            JsonNode root = jsonMapper.readTree(message.getPayload());
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("实时消息必须是 JSON 对象");
            }
            requestId = requiredText(root, "requestId");
            String action = requiredText(root, "action");
            switch (action) {
                case "AUTH" -> authenticate(session.getId(), root, requestId);
                case "SUBSCRIBE" -> subscribe(session.getId(), root, requestId);
                case "UNSUBSCRIBE" -> unsubscribe(session.getId(), root, requestId);
                case "RESYNC" -> resync(session.getId(), requestId);
                case "PING" -> ping(session.getId(), requestId);
                default -> throw new IllegalArgumentException("不支持的实时动作: " + action);
            }
        } catch (RealtimeAuthenticationRequiredException exception) {
            registry.clearAuthentication(session.getId());
            error(session.getId(), requestId, "REALTIME-AUTH-REQUIRED", exception.getMessage());
        } catch (RealtimeAuthenticationException exception) {
            registry.clearAuthentication(session.getId());
            error(session.getId(), requestId, "REALTIME-AUTH-FAILED", exception.getMessage());
        } catch (RealtimeLimitExceededException exception) {
            error(session.getId(), requestId, "REALTIME-LIMIT-EXCEEDED", exception.getMessage());
        } catch (JacksonException | IllegalArgumentException exception) {
            error(session.getId(), requestId, "REALTIME-BAD-REQUEST", exception.getMessage());
        } catch (MineException exception) {
            error(
                    session.getId(), requestId,
                    exception.errorCode().code(), exception.errorCode().message()
            );
        } catch (RealtimeTransportException exception) {
            LOGGER.warn("实时连接发送失败并移除会话，sessionId={}", session.getId());
            registry.remove(session.getId());
        } catch (RuntimeException exception) {
            LOGGER.error("实时请求处理失败，sessionId={}，requestId={}",
                    session.getId(), requestId, exception);
            error(session.getId(), requestId, "REALTIME-INTERNAL-ERROR", "实时请求暂时无法处理");
        }
    }

    /**
     * 连接关闭后移除全部订阅。
     *
     * @param session 已关闭会话
     * @param status 关闭状态
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.remove(session.getId());
    }

    private void authenticate(String sessionId, JsonNode root, String requestId) {
        RealtimePrincipal principal = authenticator.authenticate(requiredText(root, "token"));
        if (!principal.hasPermission(TELEMETRY_PERMISSION)) {
            throw new RealtimeAuthenticationException("缺少设备遥测查看权限");
        }
        registry.authenticate(sessionId, principal, requiredText(root, "token"));
        send(sessionId, "ACK", requestId, Map.of("action", "AUTH"));
    }

    private void subscribe(String sessionId, JsonNode root, String requestId) {
        refreshAuthentication(sessionId);
        List<DeviceLatestSubscription> additions = parseSubscriptions(root, false);
        registry.subscribe(sessionId, additions, limits);
        send(sessionId, "ACK", requestId, Map.of("action", "SUBSCRIBE"));
        additions.forEach(subscription -> sendSnapshot(sessionId, subscription));
    }

    private void unsubscribe(String sessionId, JsonNode root, String requestId) {
        refreshAuthentication(sessionId);
        registry.unsubscribe(sessionId, parseSubscriptions(root, true));
        send(sessionId, "ACK", requestId, Map.of("action", "UNSUBSCRIBE"));
    }

    private void resync(String sessionId, String requestId) {
        refreshAuthentication(sessionId);
        List<DeviceLatestSubscription> subscriptions = registry.subscriptions(sessionId);
        send(sessionId, "ACK", requestId, Map.of("action", "RESYNC"));
        subscriptions.forEach(subscription -> sendSnapshot(sessionId, subscription));
    }

    private void ping(String sessionId, String requestId) {
        refreshAuthentication(sessionId);
        send(sessionId, "PONG", requestId, Map.of());
    }

    private void sendSnapshot(String sessionId, DeviceLatestSubscription subscription) {
        DeviceLatestResponse snapshot = snapshots.load(
                subscription.deviceId(), subscription.propertyIds()
        );
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("topic", "DEVICE_LATEST");
        fields.put("deviceId", subscription.deviceId());
        fields.put("items", snapshot.items());
        fields.put("missingPropertyIds", snapshot.missingPropertyIds());
        send(sessionId, "SNAPSHOT", null, fields);
    }

    private List<DeviceLatestSubscription> parseSubscriptions(JsonNode root, boolean propertiesOptional) {
        JsonNode topics = root.get("topics");
        if (topics == null || !topics.isArray() || topics.isEmpty()) {
            throw new IllegalArgumentException("topics 必须是非空数组");
        }
        List<DeviceLatestSubscription> parsed = new ArrayList<>();
        for (JsonNode topic : topics) {
            if (!"DEVICE_LATEST".equals(requiredText(topic, "type"))) {
                throw new IllegalArgumentException("当前只支持 DEVICE_LATEST 主题");
            }
            List<UUID> deviceIds = uuidArray(topic, "ids", false);
            List<String> propertyIds = textArray(topic, "properties", propertiesOptional);
            for (UUID deviceId : deviceIds) {
                parsed.add(new DeviceLatestSubscription(deviceId, propertyIds));
            }
        }
        return List.copyOf(parsed);
    }

    private List<UUID> uuidArray(JsonNode root, String field, boolean optional) {
        return textArray(root, field, optional).stream().map(value -> {
            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(field + " 包含无效 UUID");
            }
        }).toList();
    }

    private List<String> textArray(JsonNode root, String field, boolean optional) {
        JsonNode values = root.get(field);
        if (values == null && optional) {
            return List.of();
        }
        if (values == null || !values.isArray() || (!optional && values.isEmpty())) {
            throw new IllegalArgumentException(field + " 必须是非空字符串数组");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isString() || value.asString().isBlank()) {
                throw new IllegalArgumentException(field + " 必须是字符串数组");
            }
            result.add(value.asString());
        }
        return List.copyOf(result);
    }

    private String requiredText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.asString();
    }

    private void refreshAuthentication(String sessionId) {
        String token = registry.token(sessionId).orElseThrow(
                RealtimeAuthenticationRequiredException::new
        );
        RealtimePrincipal principal = authenticator.authenticate(token);
        if (!principal.hasPermission(TELEMETRY_PERMISSION)) {
            throw new RealtimeAuthenticationException("缺少设备遥测查看权限");
        }
        if (registry.principal(sessionId).isEmpty()) {
            throw new RealtimeAuthenticationRequiredException();
        }
        registry.authenticate(sessionId, principal, token);
    }

    private void error(String sessionId, String requestId, String code, String message) {
        send(sessionId, "ERROR", requestId, Map.of(
                "code", code,
                "message", message == null ? "实时请求处理失败" : message
        ));
    }

    private void send(
            String sessionId,
            String type,
            String requestId,
            Map<String, Object> fields
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        if (requestId != null) {
            payload.put("requestId", requestId);
        }
        payload.putAll(fields);
        registry.send(sessionId, payload);
    }
}
