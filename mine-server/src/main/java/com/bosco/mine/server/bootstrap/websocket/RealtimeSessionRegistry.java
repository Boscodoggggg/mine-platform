package com.bosco.mine.server.bootstrap.websocket;

import com.bosco.mine.messaging.realtime.RealtimeTelemetryEvent;
import com.bosco.mine.model.message.UnifiedDeviceMessage.Quality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 保存本实例 WebSocket 会话、鉴权状态和订阅关系。
 */
public final class RealtimeSessionRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(RealtimeSessionRegistry.class);

    private final JsonMapper jsonMapper;
    private final ConcurrentMap<String, SessionState> sessions = new ConcurrentHashMap<>();

    /**
     * 创建实时会话注册表。
     *
     * @param jsonMapper JSON 编解码器
     */
    public RealtimeSessionRegistry(JsonMapper jsonMapper) {
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "JSON 编解码器不能为空");
    }

    /**
     * 注册新连接。
     *
     * @param session WebSocket 会话
     */
    public void register(WebSocketSession session) {
        Objects.requireNonNull(session, "WebSocket 会话不能为空");
        SessionState previous = sessions.putIfAbsent(session.getId(), new SessionState(session));
        if (previous != null) {
            throw new IllegalStateException("实时会话标识重复");
        }
    }

    /**
     * 移除连接及其全部订阅。
     *
     * @param sessionId 会话标识
     */
    public void remove(String sessionId) {
        sessions.remove(sessionId);
    }

    /**
     * 保存会话鉴权结果。
     *
     * @param sessionId 会话标识
     * @param principal 用户权限快照
     * @param token 已通过校验的访问令牌
     */
    public void authenticate(String sessionId, RealtimePrincipal principal, String token) {
        SessionState state = state(sessionId);
        state.principal = Objects.requireNonNull(principal, "用户不能为空");
        state.token = Objects.requireNonNull(token, "令牌不能为空");
    }

    /**
     * 清除会话鉴权快照，使后续实时事件立即停止推送。
     *
     * @param sessionId 会话标识
     */
    public void clearAuthentication(String sessionId) {
        SessionState state = state(sessionId);
        state.principal = null;
        state.token = null;
    }

    /**
     * 返回当前会话用户。
     *
     * @param sessionId 会话标识
     * @return 已鉴权用户
     */
    public Optional<RealtimePrincipal> principal(String sessionId) {
        return Optional.ofNullable(state(sessionId).principal);
    }

    /**
     * 返回当前会话令牌，用于由客户端动作触发的有效性复核。
     *
     * @param sessionId 会话标识
     * @return 已保存令牌
     */
    public Optional<String> token(String sessionId) {
        return Optional.ofNullable(state(sessionId).token);
    }

    /**
     * 原子添加一批设备最新值订阅并校验连接级限额。
     *
     * @param sessionId 会话标识
     * @param additions 新订阅
     * @param limits 连接限额
     */
    public void subscribe(
            String sessionId,
            Collection<DeviceLatestSubscription> additions,
            RealtimeLimits limits
    ) {
        SessionState state = state(sessionId);
        synchronized (state) {
            Map<UUID, LinkedHashSet<String>> merged = copySubscriptions(state.subscriptions);
            for (DeviceLatestSubscription addition : additions) {
                merged.computeIfAbsent(addition.deviceId(), ignored -> new LinkedHashSet<>())
                        .addAll(addition.propertyIds());
            }
            int propertyCount = merged.values().stream().mapToInt(Set::size).sum();
            if (merged.size() > limits.maxSubscriptions()
                    || merged.size() > limits.maxDevices()
                    || propertyCount > limits.maxProperties()) {
                throw new RealtimeLimitExceededException("实时订阅数量超过连接上限");
            }
            state.subscriptions.clear();
            state.subscriptions.putAll(merged);
        }
    }

    /**
     * 删除指定设备订阅；空属性集合表示删除整个设备订阅。
     *
     * @param sessionId 会话标识
     * @param removals 待删除订阅
     */
    public void unsubscribe(String sessionId, Collection<DeviceLatestSubscription> removals) {
        SessionState state = state(sessionId);
        synchronized (state) {
            for (DeviceLatestSubscription removal : removals) {
                LinkedHashSet<String> existing = state.subscriptions.get(removal.deviceId());
                if (existing == null) {
                    continue;
                }
                if (removal.propertyIds().isEmpty()) {
                    state.subscriptions.remove(removal.deviceId());
                } else {
                    existing.removeAll(removal.propertyIds());
                    if (existing.isEmpty()) {
                        state.subscriptions.remove(removal.deviceId());
                    }
                }
            }
        }
    }

    /**
     * 获取当前会话的不可变订阅快照。
     *
     * @param sessionId 会话标识
     * @return 设备最新值订阅
     */
    public List<DeviceLatestSubscription> subscriptions(String sessionId) {
        SessionState state = state(sessionId);
        synchronized (state) {
            return state.subscriptions.entrySet().stream()
                    .map(entry -> new DeviceLatestSubscription(
                            entry.getKey(), List.copyOf(entry.getValue())
                    ))
                    .toList();
        }
    }

    /**
     * 将已持久化的遥测增量推送给订阅了对应设备和属性的本实例连接。
     *
     * @param event 实时遥测事件
     */
    public void publish(RealtimeTelemetryEvent event) {
        Objects.requireNonNull(event, "实时遥测事件不能为空");
        for (SessionState state : sessions.values()) {
            List<String> properties = subscribedProperties(state, event.deviceId());
            if (state.principal == null || properties.isEmpty()) {
                continue;
            }
            Map<String, Double> filtered = new LinkedHashMap<>();
            Map<String, Quality> filteredQualities = new LinkedHashMap<>();
            properties.forEach(propertyId -> {
                Double value = event.values().get(propertyId);
                if (value != null) {
                    filtered.put(propertyId, value);
                    filteredQualities.put(propertyId, event.qualities().get(propertyId));
                }
            });
            if (filtered.isEmpty()) {
                continue;
            }
            Quality filteredQuality = filteredQualities.values().stream()
                    .reduce(Quality.GOOD, this::worse);
            try {
                send(state.session.getId(), Map.ofEntries(
                        Map.entry("type", "DATA"),
                        Map.entry("topic", "DEVICE_LATEST"),
                        Map.entry("deviceId", event.deviceId()),
                        Map.entry("eventId", event.eventId()),
                        Map.entry("sourceSequence", event.sourceSequence()),
                        Map.entry("occurredAt", event.occurredAt()),
                        Map.entry("receivedAt", event.receivedAt()),
                        Map.entry("quality", filteredQuality),
                        Map.entry("values", Map.copyOf(filtered)),
                        Map.entry("qualities", Map.copyOf(filteredQualities))
                ));
            } catch (RealtimeTransportException exception) {
                LOGGER.warn("实时连接发送失败并移除会话，sessionId={}", state.session.getId());
                remove(state.session.getId());
            }
        }
    }

    private Quality worse(Quality left, Quality right) {
        if (left == Quality.BAD || right == Quality.BAD) {
            return Quality.BAD;
        }
        if (left == Quality.TIME_UNTRUSTED || right == Quality.TIME_UNTRUSTED) {
            return Quality.TIME_UNTRUSTED;
        }
        if (left == Quality.UNCERTAIN || right == Quality.UNCERTAIN) {
            return Quality.UNCERTAIN;
        }
        return Quality.GOOD;
    }

    /**
     * 向指定会话发送 JSON 消息并自动增加连接内序号。
     *
     * @param sessionId 会话标识
     * @param message 消息字段
     */
    public void send(String sessionId, Map<String, Object> message) {
        SessionState state = state(sessionId);
        Map<String, Object> payload = new LinkedHashMap<>(message);
        payload.putIfAbsent("sequence", state.sequence.incrementAndGet());
        payload.putIfAbsent("serverTime", Instant.now());
        synchronized (state.session) {
            if (!state.session.isOpen()) {
                remove(sessionId);
                return;
            }
            try {
                state.session.sendMessage(new TextMessage(jsonMapper.writeValueAsString(payload)));
            } catch (IOException | JacksonException exception) {
                throw new RealtimeTransportException("实时消息发送失败", exception);
            }
        }
    }

    private SessionState state(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            throw new IllegalStateException("实时会话不存在");
        }
        return state;
    }

    private List<String> subscribedProperties(SessionState state, UUID deviceId) {
        synchronized (state) {
            Set<String> properties = state.subscriptions.get(deviceId);
            return properties == null ? List.of() : List.copyOf(properties);
        }
    }

    private Map<UUID, LinkedHashSet<String>> copySubscriptions(
            Map<UUID, LinkedHashSet<String>> source
    ) {
        Map<UUID, LinkedHashSet<String>> copied = new LinkedHashMap<>();
        source.forEach((deviceId, properties) -> copied.put(
                deviceId, new LinkedHashSet<>(properties)
        ));
        return copied;
    }

    /**
     * 设备最新值订阅。
     *
     * @param deviceId 设备标识
     * @param propertyIds 属性标识集合
     */
    public record DeviceLatestSubscription(UUID deviceId, List<String> propertyIds) {

        /**
         * 创建不可变订阅。
         */
        public DeviceLatestSubscription {
            deviceId = Objects.requireNonNull(deviceId, "设备标识不能为空");
            propertyIds = List.copyOf(Objects.requireNonNull(propertyIds, "属性集合不能为空"));
        }
    }

    private static final class SessionState {

        private final WebSocketSession session;
        private final AtomicLong sequence = new AtomicLong();
        private final Map<UUID, LinkedHashSet<String>> subscriptions = new LinkedHashMap<>();
        private volatile RealtimePrincipal principal;
        private volatile String token;

        private SessionState(WebSocketSession session) {
            this.session = session;
        }
    }

    /**
     * 表示实时订阅超过连接资源上限。
     */
    public static final class RealtimeLimitExceededException extends RuntimeException {

        /**
         * 创建连接限额异常。
         *
         * @param message 错误说明
         */
        public RealtimeLimitExceededException(String message) {
            super(message);
        }
    }

    /**
     * 表示实时消息序列化或发送失败。
     */
    public static final class RealtimeTransportException extends RuntimeException {

        /**
         * 创建实时传输异常。
         *
         * @param message 错误说明
         * @param cause 原始异常
         */
        public RealtimeTransportException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
