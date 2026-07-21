package com.bosco.mine.telemetry.alarm.outbox;

import com.bosco.mine.messaging.alarm.AlarmEvidencePayload;
import com.bosco.mine.messaging.alarm.AlarmRaisedPayload;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验告警 Outbox 只向业务交换机发布持久化白名单事件并等待确认。
 */
class RabbitAlarmOutboxPublisherTest {

    private static final UUID EVENT_ID =
            UUID.fromString("01980a3b-1200-7000-8000-000000000031");
    private static final UUID OCCURRENCE_ID =
            UUID.fromString("01980a3b-1200-7000-8000-000000000032");
    private static final UUID DEVICE_ID =
            UUID.fromString("01980a3b-1200-7000-8000-000000000033");
    private static final UUID SNAPSHOT_ID =
            UUID.fromString("01980a3b-1200-7000-8000-000000000034");
    private static final Instant RAISED_AT = Instant.parse("2026-07-17T03:00:00Z");

    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void 应发布完整持久化信封并等待Broker确认() throws Exception {
        RabbitOperations operations = mock(RabbitOperations.class);
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(operations).send(any(), any(), any(), any(CorrelationData.class));
        TelemetryOutboxRepository.ClaimedEvent event = event(OCCURRENCE_ID, DEVICE_ID);

        new RabbitAlarmOutboxPublisher(operations, 3_000L).publish(event);

        var message = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(operations).send(
                eq("mine.business.x"), eq("alarm.raised"), message.capture(),
                any(CorrelationData.class));
        assertThat(message.getValue().getMessageProperties().getDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(message.getValue().getMessageProperties().getMessageId())
                .isEqualTo(EVENT_ID.toString());
        String body = new String(message.getValue().getBody(), StandardCharsets.UTF_8);
        assertThat(body)
                .contains("\"eventType\":\"alarm.raised\"")
                .contains("\"producer\":\"mine-telemetry\"")
                .contains("\"alarmOccurrenceId\":\"" + OCCURRENCE_ID + "\"")
                .contains("\"partitionKey\":\"" + DEVICE_ID + "\"");
    }

    @Test
    void Broker确认但消息不可路由时仍应判定发布失败() throws Exception {
        RabbitOperations operations = mock(RabbitOperations.class);
        doAnswer(invocation -> {
            Message message = invocation.getArgument(2);
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.setReturned(new ReturnedMessage(
                    message, 312, "NO_ROUTE", "mine.business.x", "alarm.raised"));
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(operations).send(any(), any(), any(), any(CorrelationData.class));

        assertThatThrownBy(() -> new RabbitAlarmOutboxPublisher(operations, 3_000L)
                .publish(event(OCCURRENCE_ID, DEVICE_ID)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不可路由");
    }

    @Test
    void 聚合标识或设备分区键不一致时不得访问Broker() throws Exception {
        RabbitOperations operations = mock(RabbitOperations.class);
        TelemetryOutboxRepository.ClaimedEvent event = event(
                UUID.fromString("01980a3b-1200-7000-8000-000000000099"), DEVICE_ID);

        assertThatThrownBy(() ->
                new RabbitAlarmOutboxPublisher(operations, 3_000L).publish(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("聚合");

        verify(operations, never()).invoke(any());
    }

    private TelemetryOutboxRepository.ClaimedEvent event(
            UUID payloadOccurrenceId,
            UUID partitionDeviceId
    ) throws Exception {
        AlarmRaisedPayload payload = new AlarmRaisedPayload(
                payloadOccurrenceId,
                DEVICE_ID,
                "excavator-v1",
                "temperature-high",
                "发动机高温",
                "NUMERIC",
                "CRITICAL",
                "engine.temperature",
                new AlarmEvidencePayload(
                        new BigDecimal("96.5"), "GT",
                        new BigDecimal("95"), new BigDecimal("90"), null),
                7,
                SNAPSHOT_ID,
                "sha256:" + "a".repeat(64),
                RAISED_AT
        );
        return new TelemetryOutboxRepository.ClaimedEvent(
                EVENT_ID,
                OCCURRENCE_ID,
                "alarm.raised",
                1,
                jsonMapper.writeValueAsString(payload),
                "{\"traceId\":\"trace-alarm-1\",\"partitionKey\":\""
                        + partitionDeviceId + "\"}",
                1,
                RAISED_AT
        );
    }
}
