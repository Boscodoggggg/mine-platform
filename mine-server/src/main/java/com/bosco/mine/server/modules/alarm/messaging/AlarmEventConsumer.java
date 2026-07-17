package com.bosco.mine.server.modules.alarm.messaging;

import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.alarm.AlarmRaisedPayload;
import com.bosco.mine.messaging.alarm.AlarmRecoveredPayload;
import com.bosco.mine.messaging.topology.MineTopology;
import com.bosco.mine.server.modules.alarm.service.AlarmEventService;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Objects;

/**
 * 消费触发和恢复事件，在数据库事务提交后手工确认 RabbitMQ 消息。
 */
public final class AlarmEventConsumer implements ChannelAwareMessageListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(AlarmEventConsumer.class);
    private static final int MAX_MESSAGE_BYTES = 64 * 1024;

    private final AlarmEventService service;
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    /**
     * 创建告警事件消费者。
     *
     * @param service 告警聚合服务
     */
    public AlarmEventConsumer(AlarmEventService service) {
        this.service = Objects.requireNonNull(service, "告警事件服务不能为空");
    }

    /**
     * 处理一条告警事件，永久错误拒绝，暂时错误重新入队。
     */
    @Override
    public void onMessage(Message message, Channel channel) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            DecodedAlarm decoded = decode(message.getBody());
            if (decoded.payload() instanceof AlarmRaisedPayload raised) {
                service.applyRaised(decoded.envelope().eventId(), raised);
            } else if (decoded.payload() instanceof AlarmRecoveredPayload recovered) {
                service.applyRecovered(decoded.envelope().eventId(), recovered);
            } else {
                throw new InvalidAlarmEventMessageException("告警事件载荷类型不受支持");
            }
            channel.basicAck(deliveryTag, false);
        } catch (InvalidAlarmEventMessageException exception) {
            LOGGER.warn("拒绝不可重试的告警事件: {}", exception.getMessage());
            channel.basicReject(deliveryTag, false);
        } catch (RuntimeException exception) {
            LOGGER.error("告警事件事务未完成，消息将重新投递", exception);
            channel.basicNack(deliveryTag, false, true);
        }
    }

    private DecodedAlarm decode(byte[] body) {
        if (body == null || body.length == 0 || body.length > MAX_MESSAGE_BYTES) {
            throw new InvalidAlarmEventMessageException("告警事件消息大小不合法");
        }
        try {
            MessageEnvelope<Map<String, Object>> envelope = jsonMapper.readValue(
                    body, new TypeReference<>() { });
            if (envelope.schemaVersion() != 1) {
                throw new InvalidAlarmEventMessageException("告警事件结构版本不受支持");
            }
            if (!"mine-telemetry".equals(envelope.producer())
                    || !"mine-local".equals(envelope.tenantScope())
                    || envelope.traceId().length() > 128) {
                throw new InvalidAlarmEventMessageException("告警事件来源或追踪标识不受信任");
            }
            Object payload = switch (envelope.eventType().value()) {
                case MineTopology.ALARM_RAISED_ROUTING_KEY ->
                        jsonMapper.convertValue(envelope.payload(), AlarmRaisedPayload.class);
                case MineTopology.ALARM_RECOVERED_ROUTING_KEY ->
                        jsonMapper.convertValue(envelope.payload(), AlarmRecoveredPayload.class);
                default -> throw new InvalidAlarmEventMessageException("告警事件类型不受支持");
            };
            validateEnvelope(envelope, payload);
            return new DecodedAlarm(envelope, payload);
        } catch (InvalidAlarmEventMessageException exception) {
            throw exception;
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new InvalidAlarmEventMessageException("告警事件 JSON 无法解析", exception);
        }
    }

    private void validateEnvelope(MessageEnvelope<?> envelope, Object payload) {
        if (payload instanceof AlarmRaisedPayload raised) {
            validateIdentity(envelope, raised.alarmOccurrenceId().toString(),
                    raised.deviceId().toString(), raised.raisedAt());
        } else if (payload instanceof AlarmRecoveredPayload recovered) {
            validateIdentity(envelope, recovered.alarmOccurrenceId().toString(),
                    recovered.deviceId().toString(), recovered.recoveredAt());
        }
    }

    private void validateIdentity(
            MessageEnvelope<?> envelope,
            String occurrenceId,
            String deviceId,
            java.time.Instant factTime
    ) {
        if (!occurrenceId.equals(envelope.correlationId())
                || !deviceId.equals(envelope.partitionKey())
                || !factTime.equals(envelope.occurredAt())) {
            throw new InvalidAlarmEventMessageException("告警信封身份或业务时间与载荷不一致");
        }
    }

    /** 已验证的消息信封和强类型载荷。 */
    private record DecodedAlarm(MessageEnvelope<?> envelope, Object payload) {
    }
}
