package com.bosco.mine.server.bootstrap.websocket;

import com.bosco.mine.messaging.realtime.RealtimeLocationEvent;
import com.bosco.mine.messaging.realtime.RealtimeOperationEvent;
import com.bosco.mine.messaging.realtime.RealtimeSpatialEvent;
import com.bosco.mine.messaging.realtime.RealtimeTelemetryEvent;
import com.bosco.mine.model.message.UnifiedDeviceMessage.Quality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
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
    private final Clock clock;
    private final Duration authorizationTtl;
    private final ConcurrentMap<String, SessionState> sessions = new ConcurrentHashMap<>();

    /**
     * 创建实时会话注册表。
     *
     * @param jsonMapper JSON 编解码器
     */
    public RealtimeSessionRegistry(JsonMapper jsonMapper) {
        this(jsonMapper, Clock.systemUTC(), Duration.ofSeconds(30));
    }

    /**
     * 创建可注入时间源和授权有效期的实时会话注册表。
     *
     * @param jsonMapper JSON 编解码器
     * @param clock 服务端时间源
     * @param authorizationTtl 推送授权快照有效期
     */
    public RealtimeSessionRegistry(
            JsonMapper jsonMapper,
            Clock clock,
            Duration authorizationTtl
    ) {
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "JSON 编解码器不能为空");
        this.clock = Objects.requireNonNull(clock, "服务端时钟不能为空");
        this.authorizationTtl = Objects.requireNonNull(
                authorizationTtl, "授权快照有效期不能为空");
        if (authorizationTtl.isZero() || authorizationTtl.isNegative()) {
            throw new IllegalArgumentException("授权快照有效期必须大于 0");
        }
    }

    /**
     * 返回服务端推送授权快照有效期。
     *
     * @return 授权快照有效期
     */
    Duration authorizationTtl() {
        return authorizationTtl;
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
        synchronized (state) {
            state.principal = Objects.requireNonNull(principal, "用户不能为空");
            state.token = Objects.requireNonNull(token, "令牌不能为空");
            state.authenticatedAt = clock.instant();
        }
    }

    /**
     * 清除会话鉴权快照，使后续实时事件立即停止推送。
     *
     * @param sessionId 会话标识
     */
    public void clearAuthentication(String sessionId) {
        SessionState state = state(sessionId);
        synchronized (state) {
            state.principal = null;
            state.token = null;
            state.authenticatedAt = null;
        }
    }

    /**
     * 返回当前会话用户。
     *
     * @param sessionId 会话标识
     * @return 已鉴权用户
     */
    public Optional<RealtimePrincipal> principal(String sessionId) {
        SessionState state = state(sessionId);
        synchronized (state) {
            return Optional.ofNullable(state.principal);
        }
    }

    /**
     * 返回当前会话令牌，用于由客户端动作触发的有效性复核。
     *
     * @param sessionId 会话标识
     * @return 已保存令牌
     */
    public Optional<String> token(String sessionId) {
        SessionState state = state(sessionId);
        synchronized (state) {
            return Optional.ofNullable(state.token);
        }
    }

    /**
     * 原子添加一批设备最新值订阅并校验连接级限额。
     *
     * @param sessionId 会话标识
     * @param deviceAdditions 新设备订阅
     * @param siteAdditions 新矿区位置订阅
     * @param spatialSiteAdditions 新矿区空间事件订阅
     * @param operationSiteAdditions 新矿区运营事件订阅
     * @param limits 连接限额
     */
    public void subscribe(
            String sessionId,
            Collection<DeviceLatestSubscription> deviceAdditions,
            Collection<UUID> siteAdditions,
            Collection<UUID> spatialSiteAdditions,
            Collection<UUID> operationSiteAdditions,
            RealtimeLimits limits
    ) {
        SessionState state = state(sessionId);
        synchronized (state) {
            Map<UUID, LinkedHashSet<String>> merged = copySubscriptions(state.subscriptions);
            for (DeviceLatestSubscription addition : deviceAdditions) {
                merged.computeIfAbsent(addition.deviceId(), ignored -> new LinkedHashSet<>())
                        .addAll(addition.propertyIds());
            }
            LinkedHashSet<UUID> mergedSites = new LinkedHashSet<>(state.siteSubscriptions);
            mergedSites.addAll(siteAdditions);
            LinkedHashSet<UUID> mergedSpatialSites =
                    new LinkedHashSet<>(state.spatialSiteSubscriptions);
            mergedSpatialSites.addAll(spatialSiteAdditions);
            LinkedHashSet<UUID> mergedOperationSites =
                    new LinkedHashSet<>(state.operationSiteSubscriptions);
            mergedOperationSites.addAll(operationSiteAdditions);
            int propertyCount = merged.values().stream().mapToInt(Set::size).sum();
            if (merged.size() + mergedSites.size() + mergedSpatialSites.size()
                    + mergedOperationSites.size()
                    > limits.maxSubscriptions()
                    || merged.size() > limits.maxDevices()
                    || propertyCount > limits.maxProperties()) {
                throw new RealtimeLimitExceededException("实时订阅数量超过连接上限");
            }
            state.subscriptions.clear();
            state.subscriptions.putAll(merged);
            state.siteSubscriptions.clear();
            state.siteSubscriptions.addAll(mergedSites);
            state.spatialSiteSubscriptions.clear();
            state.spatialSiteSubscriptions.addAll(mergedSpatialSites);
            state.operationSiteSubscriptions.clear();
            state.operationSiteSubscriptions.addAll(mergedOperationSites);
        }
    }

    /**
     * 兼容只包含设备、位置和空间事件的订阅调用。
     *
     * @param sessionId 会话标识
     * @param deviceAdditions 新设备订阅
     * @param siteAdditions 新矿区位置订阅
     * @param spatialSiteAdditions 新矿区空间事件订阅
     * @param limits 连接限额
     */
    public void subscribe(
            String sessionId,
            Collection<DeviceLatestSubscription> deviceAdditions,
            Collection<UUID> siteAdditions,
            Collection<UUID> spatialSiteAdditions,
            RealtimeLimits limits
    ) {
        subscribe(
                sessionId, deviceAdditions, siteAdditions,
                spatialSiteAdditions, List.of(), limits);
    }

    /**
     * 删除指定设备订阅；空属性集合表示删除整个设备订阅。
     *
     * @param sessionId 会话标识
     * @param deviceRemovals 待删除设备订阅
     * @param siteRemovals 待删除矿区位置订阅
     * @param spatialSiteRemovals 待删除矿区空间事件订阅
     * @param operationSiteRemovals 待删除矿区运营事件订阅
     */
    public void unsubscribe(
            String sessionId,
            Collection<DeviceLatestSubscription> deviceRemovals,
            Collection<UUID> siteRemovals,
            Collection<UUID> spatialSiteRemovals,
            Collection<UUID> operationSiteRemovals
    ) {
        SessionState state = state(sessionId);
        synchronized (state) {
            for (DeviceLatestSubscription removal : deviceRemovals) {
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
            state.siteSubscriptions.removeAll(siteRemovals);
            state.spatialSiteSubscriptions.removeAll(spatialSiteRemovals);
            state.operationSiteSubscriptions.removeAll(operationSiteRemovals);
        }
    }

    /**
     * 兼容只包含设备、位置和空间事件的取消订阅调用。
     *
     * @param sessionId 会话标识
     * @param deviceRemovals 待删除设备订阅
     * @param siteRemovals 待删除矿区位置订阅
     * @param spatialSiteRemovals 待删除矿区空间事件订阅
     */
    public void unsubscribe(
            String sessionId,
            Collection<DeviceLatestSubscription> deviceRemovals,
            Collection<UUID> siteRemovals,
            Collection<UUID> spatialSiteRemovals
    ) {
        unsubscribe(
                sessionId, deviceRemovals, siteRemovals,
                spatialSiteRemovals, List.of());
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
     * 获取当前会话的矿区位置订阅快照。
     *
     * @param sessionId 会话标识
     * @return 矿区标识集合
     */
    public List<UUID> siteSubscriptions(String sessionId) {
        SessionState state = state(sessionId);
        synchronized (state) {
            return List.copyOf(state.siteSubscriptions);
        }
    }

    /**
     * 获取当前会话的矿区空间事件订阅快照。
     *
     * @param sessionId 会话标识
     * @return 矿区标识集合
     */
    public List<UUID> spatialSiteSubscriptions(String sessionId) {
        SessionState state = state(sessionId);
        synchronized (state) {
            return List.copyOf(state.spatialSiteSubscriptions);
        }
    }

    /**
     * 获取当前会话的矿区运营事件订阅快照。
     *
     * @param sessionId 会话标识
     * @return 矿区标识集合
     */
    public List<UUID> operationSiteSubscriptions(String sessionId) {
        SessionState state = state(sessionId);
        synchronized (state) {
            return List.copyOf(state.operationSiteSubscriptions);
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
            synchronized (state) {
                List<String> properties = subscribedProperties(state, event.deviceId());
                if (!authorizationFresh(state)
                        || !state.principal.hasPermission("device:telemetry:view")
                        || properties.isEmpty()) {
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
                    LOGGER.warn("实时连接发送失败并移除会话，sessionId={}",
                            state.session.getId());
                    remove(state.session.getId());
                }
            }
        }
    }

    /**
     * 将已持久化的位置增量推送给订阅了对应矿区且仍保有位置权限的本实例连接。
     *
     * @param event 实时位置事件
     */
    public void publishLocation(RealtimeLocationEvent event) {
        Objects.requireNonNull(event, "实时位置事件不能为空");
        for (SessionState state : sessions.values()) {
            synchronized (state) {
                if (!state.siteSubscriptions.contains(event.siteId())
                        || !authorizationFresh(state)
                        || !state.principal.hasPermission("mine:location:view")) {
                    continue;
                }
                try {
                    send(state.session.getId(), Map.of(
                            "type", "DATA",
                            "topic", "MINE_SITE_LOCATION",
                            "siteId", event.siteId(),
                            "item", event
                    ));
                } catch (RealtimeTransportException exception) {
                    LOGGER.warn("位置实时连接发送失败并移除会话，sessionId={}",
                            state.session.getId());
                    remove(state.session.getId());
                }
            }
        }
    }

    /**
     * 将已持久化的空间事件推送给订阅了对应矿区且仍保有事件权限的连接。
     *
     * @param event 空间业务事件
     */
    public void publishSpatial(RealtimeSpatialEvent event) {
        Objects.requireNonNull(event, "空间实时事件不能为空");
        for (SessionState state : sessions.values()) {
            synchronized (state) {
                if (!state.spatialSiteSubscriptions.contains(event.siteId())
                        || !authorizationFresh(state)
                        || !state.principal.hasPermission("mine:spatial:event:view")) {
                    continue;
                }
                try {
                    send(state.session.getId(), Map.of(
                            "type", "DATA",
                            "topic", "MINE_SITE_SPATIAL_EVENT",
                            "siteId", event.siteId(),
                            "item", event
                    ));
                } catch (RealtimeTransportException exception) {
                    LOGGER.warn("空间事件实时连接发送失败并移除会话，sessionId={}",
                            state.session.getId());
                    remove(state.session.getId());
                }
            }
        }
    }

    /**
     * 将已确认发布的运营增量推送给订阅矿区且保有运营查看权限的连接。
     *
     * @param event 运营实时事件
     */
    public void publishOperation(RealtimeOperationEvent event) {
        Objects.requireNonNull(event, "运营实时事件不能为空");
        for (SessionState state : sessions.values()) {
            synchronized (state) {
                if (!state.operationSiteSubscriptions.contains(event.siteId())
                        || !authorizationFresh(state)
                        || !state.principal.hasPermission("mine:operation:view")) {
                    continue;
                }
                try {
                    send(state.session.getId(), Map.of(
                            "type", "DATA",
                            "topic", "MINE_SITE_OPERATION",
                            "siteId", event.siteId(),
                            "businessSequence", event.businessSequence(),
                            "item", event
                    ));
                } catch (RealtimeTransportException exception) {
                    LOGGER.warn("运营实时连接发送失败并移除会话，sessionId={}",
                            state.session.getId());
                    remove(state.session.getId());
                }
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
        payload.putIfAbsent("serverTime", clock.instant());
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

    private boolean authorizationFresh(SessionState state) {
        return state.principal != null
                && state.authenticatedAt != null
                && !clock.instant().isAfter(state.authenticatedAt.plus(authorizationTtl));
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
        private final LinkedHashSet<UUID> siteSubscriptions = new LinkedHashSet<>();
        private final LinkedHashSet<UUID> spatialSiteSubscriptions = new LinkedHashSet<>();
        private final LinkedHashSet<UUID> operationSiteSubscriptions = new LinkedHashSet<>();
        private volatile RealtimePrincipal principal;
        private volatile String token;
        private volatile Instant authenticatedAt;

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
