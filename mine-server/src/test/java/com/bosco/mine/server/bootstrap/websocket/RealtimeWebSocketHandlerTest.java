package com.bosco.mine.server.bootstrap.websocket;

import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.model.message.UnifiedDeviceMessage.Quality;
import com.bosco.mine.messaging.realtime.RealtimeTelemetryEvent;
import com.bosco.mine.server.modules.device.dto.DeviceLatestResponse;
import com.bosco.mine.server.modules.device.dto.TelemetryLatestItemResponse;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验统一实时 WebSocket 的鉴权、订阅、快照和按需重同步契约。
 */
class RealtimeWebSocketHandlerTest {

    private static final UUID USER_ID = UUID.fromString("0198aef6-2e80-7000-8000-000000000001");
    private static final UUID DEVICE_ID = UUID.fromString("0198aef6-2e80-7000-8000-000000000101");

    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();
    private final AtomicInteger snapshotCalls = new AtomicInteger();
    private final AtomicBoolean snapshotAvailable = new AtomicBoolean(true);
    private final AtomicBoolean tokenValid = new AtomicBoolean(true);
    private RecordingWebSocketSession session;
    private RealtimeSessionRegistry registry;
    private RealtimeWebSocketHandler handler;

    @BeforeEach
    void 创建实时通道测试环境() throws Exception {
        session = new RecordingWebSocketSession("session-1");
        registry = new RealtimeSessionRegistry(jsonMapper);
        RealtimeAuthenticator authenticator = token -> {
            if (!"valid-token".equals(token) || !tokenValid.get()) {
                throw new RealtimeAuthenticationException("令牌无效");
            }
            return new RealtimePrincipal(USER_ID, Set.of("device:telemetry:view"));
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
        handler = new RealtimeWebSocketHandler(
                authenticator,
                snapshots,
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
}
