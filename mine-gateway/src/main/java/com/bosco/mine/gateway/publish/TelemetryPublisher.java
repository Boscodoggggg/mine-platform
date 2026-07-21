package com.bosco.mine.gateway.publish;

import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.model.message.UnifiedDeviceMessage;

/**
 * 把不同设备传输适配器产生的统一遥测可靠发布到平台消息域。
 */
@FunctionalInterface
public interface TelemetryPublisher {

    /**
     * 发布事件，并且只有 Broker 已确认持久接收时才能正常返回。
     *
     * @param envelope 标准化遥测事件
     */
    void publish(MessageEnvelope<UnifiedDeviceMessage> envelope);
}
