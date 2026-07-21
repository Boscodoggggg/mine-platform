package com.bosco.mine.gateway.mqtt;

import com.bosco.mine.gateway.normalize.InvalidDeviceMessageException;
import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.model.message.UnifiedDeviceMessage;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.io.IOException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验设备消息消费时的手工确认与失败重投边界。
 */
class DeviceMessageConsumerTest {

    @Test
    void 标准化并确认发布后才应确认原始消息() throws Exception {
        DeviceMessageConsumer.Normalizer normalizer = mock(DeviceMessageConsumer.Normalizer.class);
        DeviceMessageConsumer.Publisher publisher = mock(DeviceMessageConsumer.Publisher.class);
        Channel channel = mock(Channel.class);
        MessageEnvelope<UnifiedDeviceMessage> envelope = envelope();
        when(normalizer.normalize(any(), any())).thenReturn(envelope);
        DeviceMessageConsumer consumer = new DeviceMessageConsumer(normalizer, publisher);

        consumer.onMessage(message(42L), channel);

        verify(publisher).publish(envelope);
        verify(channel).basicAck(42L, false);
    }

    @Test
    void 非法设备报文应拒绝且不得重回原队列() throws Exception {
        DeviceMessageConsumer.Normalizer normalizer = mock(DeviceMessageConsumer.Normalizer.class);
        DeviceMessageConsumer.Publisher publisher = mock(DeviceMessageConsumer.Publisher.class);
        Channel channel = mock(Channel.class);
        when(normalizer.normalize(any(), any()))
                .thenThrow(new InvalidDeviceMessageException("身份不匹配"));
        DeviceMessageConsumer consumer = new DeviceMessageConsumer(normalizer, publisher);

        consumer.onMessage(message(43L), channel);

        verify(channel).basicReject(43L, false);
    }

    @Test
    void 内部消息未获发布确认时应重新投递原始消息() throws Exception {
        DeviceMessageConsumer.Normalizer normalizer = mock(DeviceMessageConsumer.Normalizer.class);
        DeviceMessageConsumer.Publisher publisher = mock(DeviceMessageConsumer.Publisher.class);
        Channel channel = mock(Channel.class);
        MessageEnvelope<UnifiedDeviceMessage> envelope = envelope();
        when(normalizer.normalize(any(), any())).thenReturn(envelope);
        doThrow(new IllegalStateException("publisher confirm 超时"))
                .when(publisher).publish(envelope);
        DeviceMessageConsumer consumer = new DeviceMessageConsumer(normalizer, publisher);

        consumer.onMessage(message(44L), channel);

        verify(channel).basicNack(44L, false, true);
    }

    private Message message(long deliveryTag) {
        MessageProperties properties = new MessageProperties();
        properties.setReceivedRoutingKey("mine.v1.haul-truck.device-credential-001.up.telemetry");
        properties.setDeliveryTag(deliveryTag);
        return new Message("{}".getBytes(), properties);
    }

    @SuppressWarnings("unchecked")
    private MessageEnvelope<UnifiedDeviceMessage> envelope() throws IOException {
        return mock(MessageEnvelope.class);
    }
}
