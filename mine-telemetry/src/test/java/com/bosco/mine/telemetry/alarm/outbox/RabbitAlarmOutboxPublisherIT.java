package com.bosco.mine.telemetry.alarm.outbox;

import com.bosco.mine.messaging.alarm.AlarmEvidencePayload;
import com.bosco.mine.messaging.alarm.AlarmRaisedPayload;
import com.bosco.mine.messaging.topology.MineTopology;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 使用真实 RabbitMQ 验证交换机存在但绑定漂移时不得提交 Outbox 发布状态。
 */
@Testcontainers
class RabbitAlarmOutboxPublisherIT {
    private static final UUID EVENT_ID =
            UUID.fromString("01980a3b-1200-7000-8000-000000000051");
    private static final UUID OCCURRENCE_ID =
            UUID.fromString("01980a3b-1200-7000-8000-000000000052");
    private static final UUID DEVICE_ID =
            UUID.fromString("01980a3b-1200-7000-8000-000000000053");
    private static final UUID SNAPSHOT_ID =
            UUID.fromString("01980a3b-1200-7000-8000-000000000054");
    private static final Instant NOW = Instant.parse("2026-07-17T03:00:00Z");
    private static final String QUEUE = "mine.telemetry.unroutable-test.q";

    @Container
    static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer("rabbitmq:4.1.8-management");

    private static CachingConnectionFactory connectionFactory;
    private static RabbitTemplate template;

    @BeforeAll
    static void 创建错配绑定() {
        connectionFactory = new CachingConnectionFactory(
                RABBITMQ.getHost(), RABBITMQ.getAmqpPort());
        connectionFactory.setUsername(RABBITMQ.getAdminUsername());
        connectionFactory.setPassword(RABBITMQ.getAdminPassword());
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connectionFactory.setPublisherReturns(true);
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        TopicExchange exchange = new TopicExchange(MineTopology.BUSINESS_EXCHANGE, true, false);
        Queue queue = new Queue(QUEUE, true, false, false);
        admin.declareExchange(exchange);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with("quality.#"));
        template = new RabbitTemplate(connectionFactory);
        template.setMandatory(true);
    }

    @AfterAll
    static void 关闭连接() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void 告警路由未命中队列时应重试且不得标记发布成功() throws Exception {
        TelemetryOutboxRepository repository = mock(TelemetryOutboxRepository.class);
        TelemetryOutboxRelay.RetryScheduler scheduler =
                mock(TelemetryOutboxRelay.RetryScheduler.class);
        TelemetryOutboxRepository.ClaimedEvent event = event();
        when(repository.claimById(EVENT_ID, "telemetry-it", Duration.ofSeconds(30)))
                .thenReturn(Optional.of(event));
        RabbitAlarmOutboxPublisher publisher = new RabbitAlarmOutboxPublisher(template, 5_000L);
        TelemetryOutboxRelay relay = new TelemetryOutboxRelay(
                repository,
                publisher,
                scheduler,
                "telemetry-it",
                Duration.ofSeconds(30),
                5,
                100,
                Duration.ofSeconds(1),
                Duration.ofMinutes(1),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        relay.publishById(EVENT_ID);

        verify(repository).markFailed(
                eq(EVENT_ID), eq("telemetry-it"), eq(NOW.plusSeconds(1)), contains("不可路由"));
        verify(repository, never()).markPublished(any(), any());
        verify(scheduler).schedule(EVENT_ID, NOW.plusSeconds(1));
    }

    private TelemetryOutboxRepository.ClaimedEvent event() throws Exception {
        AlarmRaisedPayload payload = new AlarmRaisedPayload(
                OCCURRENCE_ID,
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
                NOW
        );
        JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
        return new TelemetryOutboxRepository.ClaimedEvent(
                EVENT_ID,
                OCCURRENCE_ID,
                MineTopology.ALARM_RAISED_ROUTING_KEY,
                1,
                mapper.writeValueAsString(payload),
                "{\"traceId\":\"trace-unroutable\",\"partitionKey\":\"" + DEVICE_ID + "\"}",
                1,
                NOW
        );
    }
}
