package com.bosco.mine.gateway.tcp;

import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.gateway.normalize.InvalidDeviceMessageException;
import com.bosco.mine.gateway.session.DeviceSessionRegistry;
import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.MessageType;
import com.bosco.mine.model.message.TelemetryPayload;
import com.bosco.mine.model.message.UnifiedDeviceMessage;
import com.bosco.mine.protocol.sdk.tcp.TcpDecodedMessage;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 把 TCP 插件解码结果绑定到平台可信设备身份并生成统一遥测事件。
 */
public final class TcpMessageNormalizer {

    private static final MessageType EVENT_TYPE = MessageType.of("telemetry.normalized");
    private static final String PRODUCER = "mine-gateway";

    private final DeviceSessionRegistry registry;
    private final IdGenerator idGenerator;
    private final Clock clock;

    /**
     * 创建 TCP 遥测标准化器。
     *
     * @param registry 当前完整可信设备身份快照
     * @param idGenerator UUIDv7 生成器
     * @param clock 平台接收时钟
     */
    public TcpMessageNormalizer(
            DeviceSessionRegistry registry,
            IdGenerator idGenerator,
            Clock clock
    ) {
        this.registry = Objects.requireNonNull(registry, "身份注册表不能为空");
        this.idGenerator = Objects.requireNonNull(idGenerator, "标识生成器不能为空");
        this.clock = Objects.requireNonNull(clock, "平台时钟不能为空");
    }

    /**
     * 使用帧内产品和设备编码反查可信身份，并拒绝监听插件与设备配置版本不一致的消息。
     *
     * @param decoded 插件解码且已完成协议校验的中间结果
     * @param protocolVersion 当前监听器实际使用的插件版本
     * @return 可可靠发布到平台 vhost 的统一遥测事件
     */
    public MessageEnvelope<UnifiedDeviceMessage> normalize(
            TcpDecodedMessage decoded,
            String protocolVersion
    ) {
        Objects.requireNonNull(decoded, "TCP 解码消息不能为空");
        if (protocolVersion == null || protocolVersion.isBlank()) {
            throw new IllegalArgumentException("TCP 协议版本不能为空");
        }
        DeviceSessionRegistry.DeviceIdentity identity = registry.findByDeviceCode(
                        decoded.productKey(), decoded.deviceCode())
                .orElseThrow(() -> new InvalidDeviceMessageException("TCP 设备身份未登记"));
        if (!protocolVersion.equals(identity.protocolVersion())) {
            throw new InvalidDeviceMessageException("TCP 设备配置协议版本与监听插件不一致");
        }

        Instant now = Instant.now(clock);
        UUID messageId = idGenerator.next();
        UnifiedDeviceMessage message = new UnifiedDeviceMessage(
                messageId,
                UnifiedDeviceMessage.MessageKind.TELEMETRY,
                identity.productKey(),
                identity.deviceId(),
                protocolVersion,
                decoded.bootId(),
                decoded.sequence(),
                decoded.occurredAt(),
                now,
                decoded.replayed(),
                new TelemetryPayload(decoded.properties()),
                UnifiedDeviceMessage.Quality.GOOD,
                null
        );
        return new MessageEnvelope<>(
                messageId,
                EVENT_TYPE,
                1,
                decoded.occurredAt(),
                now,
                PRODUCER,
                messageId.toString(),
                null,
                identity.deviceId(),
                identity.tenantScope(),
                message
        );
    }
}
