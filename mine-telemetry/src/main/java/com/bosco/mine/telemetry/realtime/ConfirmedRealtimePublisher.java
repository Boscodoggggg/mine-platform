package com.bosco.mine.telemetry.realtime;

import com.bosco.mine.messaging.realtime.RealtimeTelemetryEvent;
import com.bosco.mine.messaging.topology.MineTopology;
import com.bosco.mine.telemetry.consumer.NormalizedTelemetryConsumer;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 将已持久化设备最新值发布到实时交换机并同步等待 Broker 确认。
 */
public final class ConfirmedRealtimePublisher
        implements NormalizedTelemetryConsumer.RealtimePublisher {

    private final RabbitOperations rabbitOperations;
    private final long confirmTimeoutMillis;
    private final JsonMapper jsonMapper;

    /**
     * 创建实时事件发布器。
     *
     * @param rabbitOperations 平台 vhost RabbitMQ 操作器
     * @param confirmTimeoutMillis 等待发布确认的最大毫秒数
     */
    public ConfirmedRealtimePublisher(
            RabbitOperations rabbitOperations,
            long confirmTimeoutMillis
    ) {
        this.rabbitOperations = Objects.requireNonNull(
                rabbitOperations, "RabbitMQ 操作器不能为空"
        );
        if (confirmTimeoutMillis < 1L) {
            throw new IllegalArgumentException("发布确认超时必须大于 0");
        }
        this.confirmTimeoutMillis = confirmTimeoutMillis;
        this.jsonMapper = JsonMapper.builder().findAndAddModules().build();
    }

    /**
     * 发布持久化 JSON 实时事件并等待 publisher confirm。
     *
     * @param event 已持久化实时遥测事件
     */
    @Override
    public void publish(RealtimeTelemetryEvent event) {
        Objects.requireNonNull(event, "实时遥测事件不能为空");
        Message message = MessageBuilder.withBody(write(event))
                .setContentType("application/json")
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(event.eventId().toString())
                .setHeader("eventType", "telemetry.latest.updated")
                .setHeader("schemaVersion", 1)
                .build();
        String routingKey = "realtime.telemetry." + event.deviceId();
        rabbitOperations.invoke(scoped -> {
            scoped.send(MineTopology.REALTIME_EXCHANGE, routingKey, message);
            scoped.waitForConfirmsOrDie(confirmTimeoutMillis);
            return null;
        });
    }

    private byte[] write(RealtimeTelemetryEvent event) {
        try {
            return jsonMapper.writeValueAsBytes(event);
        } catch (JacksonException exception) {
            throw new IllegalStateException("实时遥测事件序列化失败", exception);
        }
    }
}
