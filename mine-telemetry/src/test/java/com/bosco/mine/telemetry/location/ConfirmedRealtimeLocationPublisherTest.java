package com.bosco.mine.telemetry.location;

import com.bosco.mine.messaging.realtime.RealtimeLocationEvent;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ConfirmedRealtimeLocationPublisherTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void 应按矿区路由发送持久化信封并等待相关确认() throws Exception {
        RabbitOperations operations = mock(RabbitOperations.class);
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(operations).send(any(), any(), any(), any(CorrelationData.class));
        RealtimeLocationEvent payload = event();
        String payloadJson = canonicalJson(payload);
        var claimed = new LocationRealtimeOutboxRelay.ClaimedEvent(
                payload.eventId(), payload.sourceSequence(),
                "realtime.location.updated", 1, payloadJson, hash(payloadJson),
                canonicalJson(Map.of(
                        "partitionKey", payload.siteId().toString(),
                        "tagId", payload.tagId().toString())),
                1, payload.occurredAt());

        new ConfirmedRealtimeLocationPublisher(operations, 3_000L).publish(claimed);

        var message = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(operations).send(
                eq("mine.realtime.x"), eq("realtime.location." + payload.siteId()),
                message.capture(), any(CorrelationData.class));
        assertThat(message.getValue().getMessageProperties().getDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(message.getValue().getMessageProperties().getMessageId())
                .isEqualTo(payload.eventId().toString());
        assertThat(new String(message.getValue().getBody(), StandardCharsets.UTF_8))
                .contains("\"eventType\":\"realtime.location.updated\"")
                .contains("\"partitionKey\":\"" + payload.siteId() + "\"")
                .contains("\"tagCode\":\"tag-01\"")
                .doesNotContain("phone", "certificate");
    }

    private RealtimeLocationEvent event() {
        return new RealtimeLocationEvent(
                uuid(1), uuid(2), uuid(3), uuid(4), uuid(5), "tag-01",
                uuid(6), uuid(7), null, 31L,
                Instant.parse("2026-07-18T01:00:00Z"),
                Instant.parse("2026-07-18T01:00:01Z"),
                1D, 2D, 0.3D, 5D, 180D, 2D,
                RealtimeLocationEvent.QualityStatus.GOOD, null);
    }

    private String canonicalJson(Object value) throws JacksonException {
        Object tree = jsonMapper.treeToValue(jsonMapper.valueToTree(value), Object.class);
        return jsonMapper.writeValueAsString(canonicalize(tree));
    }

    private Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((key, item) -> result.put(
                    String.valueOf(key), canonicalize(item)));
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::canonicalize).toList();
        }
        return value;
    }

    private String hash(String value) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("019f7600-0000-7000-8000-%012d".formatted(suffix));
    }
}
