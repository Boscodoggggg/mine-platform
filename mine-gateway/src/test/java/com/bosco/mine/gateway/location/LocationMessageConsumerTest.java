package com.bosco.mine.gateway.location;

import com.bosco.mine.gateway.normalize.InvalidDeviceMessageException;
import com.bosco.mine.model.location.LocationObservation;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验原始定位报文的手工确认、死信与可重试边界。
 */
class LocationMessageConsumerTest {

    @Test
    void 只有内部定位观测发布成功后才应确认原始报文() throws Exception {
        LocationMessageConsumer.Normalizer normalizer = mock(LocationMessageConsumer.Normalizer.class);
        LocationMessageConsumer.Publisher publisher = mock(LocationMessageConsumer.Publisher.class);
        Channel channel = mock(Channel.class);
        LocationObservation observation = observation();
        when(normalizer.normalize(any(), any())).thenReturn(observation);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LocationMessageConsumer consumer = new LocationMessageConsumer(
                normalizer, publisher, new LocationIngestMetrics(registry));

        consumer.onMessage(message(42L), channel);

        var ordered = inOrder(normalizer, publisher, channel);
        ordered.verify(normalizer).normalize(any(), any());
        ordered.verify(publisher).publish(observation);
        ordered.verify(channel).basicAck(42L, false);
        assertThat(registry.get("mine.gateway.location.ingest")
                .tags("result", "accepted", "reason", "none")
                .counter().count()).isOne();
    }

    @Test
    void 永久输入错误应拒绝且不重回原队列() throws Exception {
        LocationMessageConsumer.Normalizer normalizer = mock(LocationMessageConsumer.Normalizer.class);
        LocationMessageConsumer.Publisher publisher = mock(LocationMessageConsumer.Publisher.class);
        Channel channel = mock(Channel.class);
        when(normalizer.normalize(any(), any()))
                .thenThrow(new InvalidDeviceMessageException("凭据身份不匹配"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LocationMessageConsumer consumer = new LocationMessageConsumer(
                normalizer, publisher, new LocationIngestMetrics(registry));

        consumer.onMessage(message(43L), channel);

        verify(channel).basicReject(43L, false);
        assertThat(registry.get("mine.gateway.location.ingest")
                .tags("result", "rejected", "reason", "invalid_message")
                .counter().count()).isOne();
    }

    @Test
    void 瞬时发布失败应重新投递原始报文() throws Exception {
        LocationMessageConsumer.Normalizer normalizer = mock(LocationMessageConsumer.Normalizer.class);
        LocationMessageConsumer.Publisher publisher = mock(LocationMessageConsumer.Publisher.class);
        Channel channel = mock(Channel.class);
        LocationObservation observation = observation();
        when(normalizer.normalize(any(), any())).thenReturn(observation);
        doThrow(new IllegalStateException("publisher confirm 超时"))
                .when(publisher).publish(observation);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LocationMessageConsumer consumer = new LocationMessageConsumer(
                normalizer, publisher, new LocationIngestMetrics(registry));

        consumer.onMessage(message(44L), channel);

        verify(channel).basicNack(44L, false, true);
        assertThat(registry.get("mine.gateway.location.ingest")
                .tags("result", "requeued", "reason", "publish_failure")
                .counter().count()).isOne();
    }

    @Test
    void 确认发布器应发送持久JSON并等待Broker确认() throws Exception {
        RabbitOperations template = mock(RabbitOperations.class);
        RabbitOperations scoped = mock(RabbitOperations.class);
        when(template.invoke(any())).thenAnswer(invocation -> {
            RabbitOperations.OperationsCallback<?> callback = invocation.getArgument(0);
            return callback.doInRabbit(scoped);
        });
        ConfirmedLocationPublisher publisher = new ConfirmedLocationPublisher(template, 5000);

        publisher.publish(observation());

        var messageCaptor = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(scoped).send(eq("mine.telemetry.x"), eq("location.observation"), messageCaptor.capture());
        verify(scoped).waitForConfirmsOrDie(5000);
        Message message = messageCaptor.getValue();
        assertThat(message.getMessageProperties().getDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(message.getMessageProperties().getMessageId())
                .isEqualTo("019f6d20-0000-7000-8000-000000000001");
        assertThat(message.getMessageProperties().getHeader("eventType").toString())
                .isEqualTo("location.observation");
        JsonNode json = JsonMapper.builder().build().readTree(message.getBody());
        assertThat(json.get("sourceDeviceId").asString())
                .isEqualTo("019f63e2-071f-706a-bd12-4dad7150e75d");
        assertThat(json.get("tagCode").asString()).isEqualTo("tag-001");
    }

    private Message message(long deliveryTag) {
        MessageProperties properties = new MessageProperties();
        properties.setReceivedRoutingKey(
                "mine.v1.haul-truck.device-credential-001.up.location");
        properties.setDeliveryTag(deliveryTag);
        return new Message("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), properties);
    }

    private LocationObservation observation() {
        return new LocationObservation(
                UUID.fromString("019f6d20-0000-7000-8000-000000000001"),
                UUID.fromString("019f63e2-071f-706a-bd12-4dad7150e75d"),
                "tag-001", "uwb-local", 128.5D, 64.25D, 12D,
                8.5D, 180D, 0.3D, "simulator-boot-001", 7L,
                Instant.parse("2026-07-18T08:00:00Z"),
                Instant.parse("2026-07-18T08:00:02Z"));
    }
}
