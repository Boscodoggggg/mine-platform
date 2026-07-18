package com.bosco.mine.gateway.location;

import com.bosco.mine.messaging.topology.MineTopology;
import com.bosco.mine.model.location.LocationObservation;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 在平台 vhost 上发布持久化定位观测，并同步等待 RabbitMQ publisher confirm。
 */
public final class ConfirmedLocationPublisher implements LocationMessageConsumer.Publisher {

    private static final int SCHEMA_VERSION = 1;

    private final RabbitOperations rabbitOperations;
    private final long confirmTimeoutMillis;
    private final JsonMapper jsonMapper;

    /**
     * 创建带发布确认的定位观测发布器。
     *
     * @param rabbitOperations 已连接 {@code /mine-platform} 的操作模板
     * @param confirmTimeoutMillis 等待 Broker 确认的最大毫秒数
     */
    public ConfirmedLocationPublisher(RabbitOperations rabbitOperations, long confirmTimeoutMillis) {
        this.rabbitOperations = Objects.requireNonNull(rabbitOperations, "rabbitOperations 不能为空");
        if (confirmTimeoutMillis < 1L) {
            throw new IllegalArgumentException("confirmTimeoutMillis 必须大于 0");
        }
        this.confirmTimeoutMillis = confirmTimeoutMillis;
        this.jsonMapper = JsonMapper.builder().build();
    }

    /**
     * 发布强类型观测，确认成功后才返回。
     *
     * @param observation 待发布观测
     */
    @Override
    public void publish(LocationObservation observation) {
        Objects.requireNonNull(observation, "observation 不能为空");
        Message message = MessageBuilder.withBody(write(observation))
                .setContentType("application/json")
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(observation.observationId().toString())
                .setHeader("eventType", MineTopology.LOCATION_OBSERVATION_ROUTING_KEY)
                .setHeader("schemaVersion", SCHEMA_VERSION)
                .build();
        rabbitOperations.invoke(scoped -> {
            scoped.send(
                    MineTopology.TELEMETRY_EXCHANGE,
                    MineTopology.LOCATION_OBSERVATION_ROUTING_KEY,
                    message);
            scoped.waitForConfirmsOrDie(confirmTimeoutMillis);
            return null;
        });
    }

    private byte[] write(LocationObservation observation) {
        try {
            return jsonMapper.writeValueAsBytes(observation);
        } catch (JacksonException exception) {
            throw new IllegalStateException("定位观测序列化失败", exception);
        }
    }
}
