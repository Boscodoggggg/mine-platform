package com.bosco.mine.server.bootstrap.websocket;

import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.model.message.UnifiedDeviceMessage.Quality;
import com.bosco.mine.messaging.realtime.RealtimeSpatialEvent;
import com.bosco.mine.messaging.realtime.RealtimeTelemetryEvent;
import com.bosco.mine.messaging.realtime.RealtimeLocationEvent;
import com.bosco.mine.messaging.realtime.RealtimeOperationEvent;
import com.bosco.mine.server.modules.device.dto.DeviceLatestResponse;
import com.bosco.mine.server.modules.device.dto.TelemetryLatestItemResponse;
import com.bosco.mine.server.modules.mine.dto.CurrentLocationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验统一实时 WebSocket 的鉴权、订阅、快照和按需重同步契约。
 */
class RealtimeWebSocketHandlerTest {

    private static final UUID USER_ID = UUID.fromString("0198aef6-2e80-7000-8000-000000000001");
    private static final UUID DEVICE_ID = UUID.fromString("0198aef6-2e80-7000-8000-000000000101");
    private static final UUID SITE_ID = UUID.fromString("0198aef6-2e80-7000-8000-000000000401");
    private static final UUID OTHER_SITE_ID = UUID.fromString("0198aef6-2e80-7000-8000-000000000402");

    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();
    private final AtomicInteger snapshotCalls = new AtomicInteger();
    private final AtomicBoolean snapshotAvailable = new AtomicBoolean(true);
    private final AtomicBoolean tokenValid = new AtomicBoolean(true);
    private final AtomicBoolean siteAuthorized = new AtomicBoolean(true);
    private final AtomicInteger locationSnapshotCalls = new AtomicInteger();
    private final AtomicReference<Set<String>> permissions = new AtomicReference<>(Set.of(
            "device:telemetry:view", "mine:location:view", "mine:spatial:event:view",
            "mine:operation:view"));
    private RecordingWebSocketSession session;
    private MutableClock clock;
    private RealtimeSessionRegistry registry;
    private RealtimeWebSocketHandler handler;

    @BeforeEach
    void 创建实时通道测试环境() throws Exception {
        session = new RecordingWebSocketSession("session-1");
        clock = new MutableClock(Instant.parse("2026-07-15T00:00:00Z"));
        registry = new RealtimeSessionRegistry(jsonMapper, clock, Duration.ofSeconds(30));
        RealtimeAuthenticator authenticator = token -> {
            if (!"valid-token".equals(token) || !tokenValid.get()) {
                throw new RealtimeAuthenticationException("令牌无效");
            }
            return new RealtimePrincipal(USER_ID, permissions.get());
        };
        RealtimeSnapshotProvider snapshots = (deviceId, propertyIds) -> {
            snapshotCalls.incrementAndGet();
            if (!snapshotAvailable.get()) {
                throw new MineException(
                        new ErrorCode("TELEMETRY-UNAVAILABLE", "遥测最新值暂时不可用"),
                        Map.of("deviceId", deviceId)
                );
            }
            return new DeviceLatestResponse(
                    deviceId,
                    List.of(new TelemetryLatestItemResponse(
                            "speed",
                            12.5D,
                            Instant.parse("2026-07-15T01:02:03Z"),
                            Instant.parse("2026-07-15T01:02:04Z"),
                            Quality.GOOD,
                            UUID.fromString("0198aef6-2e80-7000-8000-000000000201"),
                            "boot-a",
                            7L,
                            "REDIS"
                    )),
                    List.of("rpm")
            );
        };
        RealtimeSiteAuthorizer siteAuthorizer = (userId, siteId) -> {
            if (!USER_ID.equals(userId) || !SITE_ID.equals(siteId) || !siteAuthorized.get()) {
                throw new MineException(
                        new ErrorCode("MINE-SCOPE-403-002", "无权访问该组织或矿区"), Map.of());
            }
        };
        RealtimeLocationSnapshotProvider locationSnapshots = (userId, siteId) -> {
            siteAuthorizer.requireAccess(userId, siteId);
            locationSnapshotCalls.incrementAndGet();
            RealtimeLocationEvent event = locationEvent(SITE_ID, 71L);
            return new CurrentLocationResponse(
                    siteId, event.sourceSequence(), "REDIS", false,
                    List.of(CurrentLocationResponse.Item.from(event)));
        };
        handler = new RealtimeWebSocketHandler(
                authenticator,
                snapshots,
                locationSnapshots,
                siteAuthorizer,
                registry,
                new RealtimeLimits(4, 8, 16, 16_384)
        );
        handler.afterConnectionEstablished(session);
    }

    @Test
    void 未鉴权连接不得订阅且不得查询快照() throws Exception {
        send(subscription("sub-1", List.of("speed")));

        JsonNode error = lastMessage();
        assertThat(error.get("type").asString()).isEqualTo("ERROR");
        assertThat(error.get("code").asString()).isEqualTo("REALTIME-AUTH-REQUIRED");
        assertThat(snapshotCalls).hasValue(0);
    }

    @Test
    void 鉴权后订阅应确认并只查询一次当前快照() throws Exception {
        authenticate();
        session.clearMessages();

        send(subscription("sub-1", List.of("speed", "rpm")));

        assertThat(session.messages()).hasSize(2);
        JsonNode acknowledgement = message(0);
        JsonNode snapshot = message(1);
        assertThat(acknowledgement.get("type").asString()).isEqualTo("ACK");
        assertThat(acknowledgement.get("requestId").asString()).isEqualTo("sub-1");
        assertThat(snapshot.get("type").asString()).isEqualTo("SNAPSHOT");
        assertThat(snapshot.get("topic").asString()).isEqualTo("DEVICE_LATEST");
        assertThat(snapshot.get("deviceId").asString()).isEqualTo(DEVICE_ID.toString());
        assertThat(snapshot.get("items").get(0).get("propertyId").asString()).isEqualTo("speed");
        assertThat(snapshot.get("missingPropertyIds").get(0).asString()).isEqualTo("rpm");
        assertThat(snapshotCalls).hasValue(1);
    }

    @Test
    void 正常订阅后只有显式重同步才再次查询快照() throws Exception {
        authenticate();
        send(subscription("sub-1", List.of("speed")));
        session.clearMessages();

        send("{\"action\":\"PING\",\"requestId\":\"ping-1\"}");
        assertThat(lastMessage().get("type").asString()).isEqualTo("PONG");
        assertThat(snapshotCalls).hasValue(1);

        send("{\"action\":\"RESYNC\",\"requestId\":\"sync-1\"}");
        assertThat(session.messages()).hasSize(3);
        assertThat(message(1).get("type").asString()).isEqualTo("ACK");
        assertThat(message(2).get("type").asString()).isEqualTo("SNAPSHOT");
        assertThat(snapshotCalls).hasValue(2);
    }

    @Test
    void 最新值临时不可用时应返回稳定错误且连接保持可用() throws Exception {
        authenticate();
        session.clearMessages();
        snapshotAvailable.set(false);

        send(subscription("sub-unavailable", List.of("speed")));

        assertThat(session.messages()).hasSize(2);
        assertThat(message(0).get("type").asString()).isEqualTo("ACK");
        JsonNode error = message(1);
        assertThat(error.get("type").asString()).isEqualTo("ERROR");
        assertThat(error.get("requestId").asString()).isEqualTo("sub-unavailable");
        assertThat(error.get("code").asString()).isEqualTo("TELEMETRY-UNAVAILABLE");

        send("{\"action\":\"PING\",\"requestId\":\"ping-after-error\"}");
        assertThat(lastMessage().get("type").asString()).isEqualTo("PONG");
    }

    @Test
    void 心跳应重新校验令牌且过期后停止实时推送() throws Exception {
        authenticate();
        send(subscription("sub-1", List.of("speed")));
        session.clearMessages();
        tokenValid.set(false);

        send("{\"action\":\"PING\",\"requestId\":\"ping-expired\"}");

        assertThat(lastMessage().get("code").asString()).isEqualTo("REALTIME-AUTH-FAILED");
        session.clearMessages();
        registry.publish(new RealtimeTelemetryEvent(
                UUID.randomUUID(), DEVICE_ID, 44L,
                Instant.parse("2026-07-15T02:00:04Z"),
                Instant.parse("2026-07-15T02:00:05Z"),
                Quality.GOOD, "boot-a", 9L, Map.of("speed", 14D)
        ));
        assertThat(session.messages()).isEmpty();
    }

    @Test
    void 超过属性订阅上限应拒绝且不查询快照() throws Exception {
        authenticate();
        session.clearMessages();
        List<String> properties = new ArrayList<>();
        for (int index = 0; index < 17; index++) {
            properties.add("property" + index);
        }

        send(subscription("sub-limit", properties));

        assertThat(lastMessage().get("code").asString()).isEqualTo("REALTIME-LIMIT-EXCEEDED");
        assertThat(snapshotCalls).hasValue(0);
    }

    @Test
    void 实时事件只应推送订阅设备和属性且不查询数据库() throws Exception {
        authenticate();
        send(subscription("sub-1", List.of("speed")));
        session.clearMessages();

        registry.publish(new RealtimeTelemetryEvent(
                UUID.fromString("0198aef6-2e80-7000-8000-000000000301"),
                DEVICE_ID,
                42L,
                Instant.parse("2026-07-15T02:00:00Z"),
                Instant.parse("2026-07-15T02:00:01Z"),
                Quality.BAD,
                "boot-a",
                8L,
                Map.of("speed", 13.5D, "rpm", 1800D),
                Map.of("speed", Quality.GOOD, "rpm", Quality.BAD)
        ));

        assertThat(session.messages()).hasSize(1);
        JsonNode data = lastMessage();
        assertThat(data.get("type").asString()).isEqualTo("DATA");
        assertThat(data.get("sourceSequence").asLong()).isEqualTo(42L);
        assertThat(data.get("values").size()).isEqualTo(1);
        assertThat(data.get("values").get("speed").asDouble()).isEqualTo(13.5D);
        assertThat(data.get("values").get("rpm")).isNull();
        assertThat(data.get("qualities").size()).isEqualTo(1);
        assertThat(data.get("qualities").get("speed").asString()).isEqualTo("GOOD");
        assertThat(data.get("qualities").get("rpm")).isNull();
        assertThat(data.get("quality").asString()).isEqualTo("GOOD");
        assertThat(snapshotCalls).hasValue(1);

        registry.publish(new RealtimeTelemetryEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                43L,
                Instant.parse("2026-07-15T02:00:02Z"),
                Instant.parse("2026-07-15T02:00:03Z"),
                Quality.GOOD,
                "boot-b",
                1L,
                Map.of("speed", 1D)
        ));
        assertThat(session.messages()).hasSize(1);
    }

    @Test
    void 矿区位置订阅应返回快照且只推送本矿事件() throws Exception {
        authenticate();
        session.clearMessages();

        send(siteSubscription("site-sub-1", SITE_ID));

        assertThat(session.messages()).hasSize(2);
        assertThat(message(0).get("type").asString()).isEqualTo("ACK");
        JsonNode snapshot = message(1);
        assertThat(snapshot.get("type").asString()).isEqualTo("SNAPSHOT");
        assertThat(snapshot.get("topic").asString()).isEqualTo("MINE_SITE_LOCATION");
        assertThat(snapshot.get("siteId").asString()).isEqualTo(SITE_ID.toString());
        assertThat(snapshot.get("items").get(0).get("tagCode").asString())
                .isEqualTo("tag-001");
        assertThat(locationSnapshotCalls).hasValue(1);
        session.clearMessages();

        registry.publishLocation(locationEvent(OTHER_SITE_ID, 72L));
        assertThat(session.messages()).isEmpty();

        registry.publishLocation(locationEvent(SITE_ID, 73L));
        assertThat(session.messages()).hasSize(1);
        JsonNode data = lastMessage();
        assertThat(data.get("type").asString()).isEqualTo("DATA");
        assertThat(data.get("topic").asString()).isEqualTo("MINE_SITE_LOCATION");
        assertThat(data.get("siteId").asString()).isEqualTo(SITE_ID.toString());
        assertThat(data.get("item").get("sourceSequence").asLong()).isEqualTo(73L);
        assertThat(data.toString()).doesNotContain("phone", "idCard");
    }

    @Test
    void 只有位置权限的用户也应能建立矿区实时订阅() throws Exception {
        permissions.set(Set.of("mine:location:view"));

        authenticate();
        session.clearMessages();
        send(siteSubscription("site-only-sub", SITE_ID));

        assertThat(session.messages()).hasSize(2);
        assertThat(message(0).get("type").asString()).isEqualTo("ACK");
        assertThat(message(1).get("topic").asString()).isEqualTo("MINE_SITE_LOCATION");
    }

    @Test
    void 矿区授权撤销后下一次心跳应使连接失效并停止位置推送() throws Exception {
        authenticate();
        send(siteSubscription("site-sub-1", SITE_ID));
        session.clearMessages();
        siteAuthorized.set(false);

        send("{\"action\":\"PING\",\"requestId\":\"ping-revoked\"}");

        assertThat(lastMessage().get("code").asString()).isEqualTo("REALTIME-AUTH-FAILED");
        session.clearMessages();
        registry.publishLocation(locationEvent(SITE_ID, 74L));
        assertThat(session.messages()).isEmpty();
    }

    @Test
    void 矿区位置重同步应复用统一序号并重新读取快照() throws Exception {
        authenticate();
        send(siteSubscription("site-sub-1", SITE_ID));
        session.clearMessages();

        send("{\"action\":\"RESYNC\",\"requestId\":\"site-sync-1\"}");

        assertThat(session.messages()).hasSize(2);
        assertThat(message(0).get("type").asString()).isEqualTo("ACK");
        assertThat(message(1).get("topic").asString()).isEqualTo("MINE_SITE_LOCATION");
        assertThat(message(1).get("sequence").asLong())
                .isGreaterThan(message(0).get("sequence").asLong());
        assertThat(locationSnapshotCalls).hasValue(2);
    }

    @Test
    void 空间事件订阅应使用独立权限和白名单主题() throws Exception {
        permissions.set(Set.of("mine:spatial:event:view"));
        authenticate();
        session.clearMessages();

        send(spatialSubscription("spatial-sub-1", SITE_ID));

        assertThat(session.messages()).singleElement()
                .satisfies(message -> {
                    try {
                        assertThat(jsonMapper.readTree(message.getPayload())
                                .get("type").asString()).isEqualTo("ACK");
                    } catch (Exception exception) {
                        throw new AssertionError(exception);
                    }
                });
        session.clearMessages();

        registry.publishSpatial(spatialEvent(OTHER_SITE_ID, 81L));
        assertThat(session.messages()).isEmpty();
        registry.publishSpatial(spatialEvent(SITE_ID, 82L));

        JsonNode data = lastMessage();
        assertThat(data.get("type").asString()).isEqualTo("DATA");
        assertThat(data.get("topic").asString()).isEqualTo("MINE_SITE_SPATIAL_EVENT");
        assertThat(data.get("siteId").asString()).isEqualTo(SITE_ID.toString());
        assertThat(data.get("item").get("ingestSequence").asLong()).isEqualTo(82L);
    }

    @Test
    void 空间事件权限撤销后下一次动作应停止推送() throws Exception {
        permissions.set(Set.of("mine:spatial:event:view"));
        authenticate();
        send(spatialSubscription("spatial-sub-1", SITE_ID));
        session.clearMessages();
        permissions.set(Set.of());

        send("{\"action\":\"PING\",\"requestId\":\"spatial-revoked\"}");

        assertThat(lastMessage().get("code").asString()).isEqualTo("REALTIME-AUTH-FAILED");
        session.clearMessages();
        registry.publishSpatial(spatialEvent(SITE_ID, 83L));
        assertThat(session.messages()).isEmpty();
    }

    @Test
    void 矿区授权撤销后重新鉴权不得恢复已有空间订阅推送() throws Exception {
        permissions.set(Set.of("mine:spatial:event:view"));
        authenticate();
        send(spatialSubscription("spatial-sub-1", SITE_ID));
        session.clearMessages();
        siteAuthorized.set(false);

        send("{\"action\":\"PING\",\"requestId\":\"scope-revoked\"}");
        assertThat(lastMessage().get("code").asString()).isEqualTo("REALTIME-AUTH-FAILED");
        session.clearMessages();

        send("{\"action\":\"AUTH\",\"requestId\":\"reauth-revoked\","
                + "\"token\":\"valid-token\"}");

        assertThat(lastMessage().get("code").asString()).isEqualTo("REALTIME-AUTH-FAILED");
        session.clearMessages();
        registry.publishSpatial(spatialEvent(SITE_ID, 84L));
        assertThat(session.messages()).isEmpty();
    }

    @Test
    void 运营订阅必须按独立权限和矿区作用域推送连续业务序号() throws Exception {
        permissions.set(Set.of("mine:operation:view"));
        authenticate();
        session.clearMessages();

        send(operationSubscription("operation-sub-1", SITE_ID));

        assertThat(lastMessage().get("type").asString()).isEqualTo("ACK");
        assertThat(registry.operationSiteSubscriptions(session.getId()))
                .containsExactly(SITE_ID);
        session.clearMessages();

        registry.publishOperation(operationEvent(OTHER_SITE_ID, 91L));
        assertThat(session.messages()).isEmpty();
        registry.publishOperation(operationEvent(SITE_ID, 92L));

        JsonNode data = lastMessage();
        assertThat(data.get("type").asString()).isEqualTo("DATA");
        assertThat(data.get("topic").asString()).isEqualTo("MINE_SITE_OPERATION");
        assertThat(data.get("siteId").asString()).isEqualTo(SITE_ID.toString());
        assertThat(data.get("businessSequence").asLong()).isEqualTo(92L);
    }

    @Test
    void 运营权限撤销后下一次动作必须清除鉴权并立即停止推送() throws Exception {
        permissions.set(Set.of("mine:operation:view"));
        authenticate();
        send(operationSubscription("operation-sub-1", SITE_ID));
        session.clearMessages();
        permissions.set(Set.of());

        send("{\"action\":\"PING\",\"requestId\":\"operation-revoked\"}");

        assertThat(lastMessage().get("code").asString()).isEqualTo("REALTIME-AUTH-FAILED");
        session.clearMessages();
        registry.publishOperation(operationEvent(SITE_ID, 93L));
        assertThat(session.messages()).isEmpty();
    }

    @Test
    void 空闲连接的授权快照过期后必须自动停止运营推送() throws Exception {
        permissions.set(Set.of("mine:operation:view"));
        authenticate();
        send(operationSubscription("operation-sub-1", SITE_ID));
        session.clearMessages();
        permissions.set(Set.of());

        clock.advance(Duration.ofSeconds(31));
        registry.publishOperation(operationEvent(SITE_ID, 94L));

        assertThat(session.messages()).isEmpty();
    }

    private void authenticate() throws Exception {
        send("{\"action\":\"AUTH\",\"requestId\":\"auth-1\",\"token\":\"valid-token\"}");
        assertThat(lastMessage().get("type").asString()).isEqualTo("ACK");
    }

    private String subscription(String requestId, List<String> propertyIds) throws Exception {
        return jsonMapper.writeValueAsString(Map.of(
                "action", "SUBSCRIBE",
                "requestId", requestId,
                "topics", List.of(Map.of(
                        "type", "DEVICE_LATEST",
                        "ids", List.of(DEVICE_ID.toString()),
                        "properties", propertyIds
                ))
        ));
    }

    private String siteSubscription(String requestId, UUID siteId) throws Exception {
        return jsonMapper.writeValueAsString(Map.of(
                "action", "SUBSCRIBE",
                "requestId", requestId,
                "topics", List.of(Map.of(
                        "type", "MINE_SITE_LOCATION",
                        "ids", List.of(siteId.toString(), siteId.toString())
                ))
        ));
    }

    private String spatialSubscription(String requestId, UUID siteId) throws Exception {
        return jsonMapper.writeValueAsString(Map.of(
                "action", "SUBSCRIBE",
                "requestId", requestId,
                "topics", List.of(Map.of(
                        "type", "MINE_SITE_SPATIAL_EVENT",
                        "ids", List.of(siteId.toString(), siteId.toString())
                ))
        ));
    }

    private String operationSubscription(String requestId, UUID siteId) throws Exception {
        return jsonMapper.writeValueAsString(Map.of(
                "action", "SUBSCRIBE",
                "requestId", requestId,
                "topics", List.of(Map.of(
                        "type", "MINE_SITE_OPERATION",
                        "ids", List.of(siteId.toString(), siteId.toString())
                ))
        ));
    }

    private RealtimeLocationEvent locationEvent(UUID siteId, long sequence) {
        String suffix = String.format("%012d", sequence);
        return new RealtimeLocationEvent(
                UUID.fromString("0198aef6-2e80-7000-8000-" + suffix),
                UUID.fromString("0198aef6-2e80-7000-8000-000000000501"),
                siteId,
                UUID.fromString("0198aef6-2e80-7000-8000-000000000502"),
                UUID.fromString("0198aef6-2e80-7000-8000-000000000503"),
                "tag-001",
                UUID.fromString("0198aef6-2e80-7000-8000-000000000504"),
                null,
                null,
                sequence,
                Instant.parse("2026-07-15T02:00:00Z").plusSeconds(sequence),
                Instant.parse("2026-07-15T02:00:01Z").plusSeconds(sequence),
                120.5D,
                88.25D,
                null,
                4.5D,
                90D,
                0.5D,
                RealtimeLocationEvent.QualityStatus.GOOD,
                null
        );
    }

    private RealtimeSpatialEvent spatialEvent(UUID siteId, long sequence) {
        String suffix = String.format("%012d", sequence);
        return new RealtimeSpatialEvent(
                UUID.fromString("0198aef6-2e80-7000-8000-" + suffix),
                UUID.fromString("0198aef6-2e80-7000-8000-000000000501"),
                siteId, RealtimeSpatialEvent.ZoneType.GEOFENCE,
                UUID.fromString("0198aef6-2e80-7000-8000-000000000701"),
                UUID.fromString("0198aef6-2e80-7000-8000-000000000702"),
                UUID.fromString("0198aef6-2e80-7000-8000-000000000503"),
                UUID.fromString("0198aef6-2e80-7000-8000-000000000703"), null,
                RealtimeSpatialEvent.EventType.ENTER,
                UUID.fromString("0198aef6-2e80-7000-8000-000000000704"),
                RealtimeSpatialEvent.StableState.OUTSIDE,
                RealtimeSpatialEvent.StableState.INSIDE,
                2, sequence, 7,
                Instant.parse("2026-07-15T02:00:00Z").plusSeconds(sequence),
                new RealtimeSpatialEvent.Evidence(
                        120.5D, 88.25D, null, "INSIDE", "GOOD", null,
                        "sha256:" + "a".repeat(64)));
    }

    private RealtimeOperationEvent operationEvent(UUID siteId, long sequence) {
        String suffix = String.format("%012d", sequence);
        UUID eventId = UUID.fromString("0198aef6-2e80-7000-8000-" + suffix);
        return new RealtimeOperationEvent(
                eventId,
                UUID.fromString("0198aef6-2e80-7000-8000-000000000501"),
                siteId,
                RealtimeOperationEvent.AggregateType.OPERATION_RECORD,
                UUID.fromString("0198aef6-2e80-7000-8000-000000000801"),
                "RECORD_COMPLETED",
                sequence,
                Instant.parse("2026-07-15T02:00:00Z").plusSeconds(sequence),
                Map.of("operationRecordId",
                        "0198aef6-2e80-7000-8000-000000000801")
        );
    }

    private void send(String payload) throws Exception {
        handler.handleMessage(session, new TextMessage(payload));
    }

    private JsonNode lastMessage() throws Exception {
        return message(session.messages().size() - 1);
    }

    private JsonNode message(int index) throws Exception {
        return jsonMapper.readTree(session.messages().get(index).getPayload());
    }

    /**
     * 记录服务端文本帧的测试会话。
     */
    private static final class RecordingWebSocketSession implements WebSocketSession {

        private final String id;
        private final Map<String, Object> attributes = new HashMap<>();
        private final List<TextMessage> messages = new ArrayList<>();
        private boolean open = true;
        private int textMessageSizeLimit = 64 * 1024;
        private int binaryMessageSizeLimit = 64 * 1024;

        private RecordingWebSocketSession(String id) {
            this.id = id;
        }

        private List<TextMessage> messages() {
            return List.copyOf(messages);
        }

        private void clearMessages() {
            messages.clear();
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public URI getUri() {
            return URI.create("ws://localhost/api/v1/realtime");
        }

        @Override
        public HttpHeaders getHandshakeHeaders() {
            return HttpHeaders.EMPTY;
        }

        @Override
        public Map<String, Object> getAttributes() {
            return attributes;
        }

        @Override
        public Principal getPrincipal() {
            return null;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 8080);
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 18080);
        }

        @Override
        public String getAcceptedProtocol() {
            return null;
        }

        @Override
        public void setTextMessageSizeLimit(int messageSizeLimit) {
            textMessageSizeLimit = messageSizeLimit;
        }

        @Override
        public int getTextMessageSizeLimit() {
            return textMessageSizeLimit;
        }

        @Override
        public void setBinaryMessageSizeLimit(int messageSizeLimit) {
            binaryMessageSizeLimit = messageSizeLimit;
        }

        @Override
        public int getBinaryMessageSizeLimit() {
            return binaryMessageSizeLimit;
        }

        @Override
        public List<WebSocketExtension> getExtensions() {
            return List.of();
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) {
            messages.add((TextMessage) message);
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }

        @Override
        public void close(CloseStatus status) throws IOException {
            open = false;
        }
    }

    /**
     * 支持测试主动推进时间的时钟。
     */
    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
