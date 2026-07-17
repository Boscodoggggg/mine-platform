package com.bosco.mine.server.modules.alarm.messaging;

import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.MessageType;
import com.bosco.mine.messaging.alarm.AlarmEvidencePayload;
import com.bosco.mine.messaging.alarm.AlarmRaisedPayload;
import com.bosco.mine.messaging.topology.MineTopology;
import com.bosco.mine.server.modules.alarm.service.AlarmEventService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 校验告警消息仅在事务成功后确认，并区分永久和暂时错误。
 */
class AlarmEventConsumerTest {
    private static final UUID EVENT_ID = UUID.fromString("019f64e5-a000-7000-8000-000000000421");
    private static final UUID ALARM_ID = UUID.fromString("019f64e5-a000-7000-8000-000000000422");
    private static final UUID DEVICE_ID = UUID.fromString("019f64e5-a000-7000-8000-000000000423");
    private static final UUID SNAPSHOT_ID = UUID.fromString("019f64e5-a000-7000-8000-000000000424");
    private static final Instant RAISED_AT = Instant.parse("2026-07-17T01:00:00Z");

    @Test
    void 事务成功后应确认告警事件() throws Exception {
        AlarmEventService service = mock(AlarmEventService.class);
        Channel channel = mock(Channel.class);

        new AlarmEventConsumer(service).onMessage(message(71L, body(ALARM_ID.toString())), channel);

        verify(service).applyRaised(any(UUID.class), any(AlarmRaisedPayload.class));
        verify(channel).basicAck(71L, false);
    }

    @Test
    void 事务暂时失败时应重新投递() throws Exception {
        AlarmEventService service = mock(AlarmEventService.class);
        doThrow(new IllegalStateException("数据库暂时不可用"))
                .when(service).applyRaised(any(UUID.class), any(AlarmRaisedPayload.class));
        Channel channel = mock(Channel.class);

        new AlarmEventConsumer(service).onMessage(message(72L, body(ALARM_ID.toString())), channel);

        verify(channel).basicNack(72L, false, true);
    }

    @Test
    void 信封关联标识与载荷不一致应永久拒绝() throws Exception {
        AlarmEventService service = mock(AlarmEventService.class);
        Channel channel = mock(Channel.class);

        new AlarmEventConsumer(service).onMessage(message(73L, body(UUID.randomUUID().toString())), channel);

        verify(channel).basicReject(73L, false);
    }

    @Test
    void 服务判定身份冲突应永久拒绝() throws Exception {
        AlarmEventService service = mock(AlarmEventService.class);
        doThrow(new InvalidAlarmEventMessageException("身份冲突"))
                .when(service).applyRaised(any(UUID.class), any(AlarmRaisedPayload.class));
        Channel channel = mock(Channel.class);

        new AlarmEventConsumer(service).onMessage(message(74L, body(ALARM_ID.toString())), channel);

        verify(channel).basicReject(74L, false);
    }

    private byte[] body(String correlationId) throws Exception {
        AlarmRaisedPayload payload = new AlarmRaisedPayload(
                ALARM_ID, DEVICE_ID, "haul-truck", "engine-hot", "发动机过热",
                "NUMERIC", "CRITICAL", "engineTemp",
                new AlarmEvidencePayload(
                        new BigDecimal("90"), "GT", new BigDecimal("80"),
                        new BigDecimal("75"), null),
                7, SNAPSHOT_ID, "sha256:" + "a".repeat(64), RAISED_AT
        );
        MessageEnvelope<AlarmRaisedPayload> envelope = new MessageEnvelope<>(
                EVENT_ID, MessageType.of(MineTopology.ALARM_RAISED_ROUTING_KEY), 1,
                RAISED_AT, RAISED_AT.plusSeconds(1), "mine-telemetry", "trace-alarm",
                correlationId, DEVICE_ID.toString(), "mine-local", payload
        );
        return JsonMapper.builder().findAndAddModules().build().writeValueAsBytes(envelope);
    }

    private Message message(long deliveryTag, byte[] body) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(deliveryTag);
        properties.setReceivedRoutingKey(MineTopology.ALARM_RAISED_ROUTING_KEY);
        return new Message(body, properties);
    }
}
