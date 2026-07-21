package com.bosco.mine.gateway.configuration;

import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.configuration.ConfigReleasePayload;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 受限解码并校验配置发布消息信封。
 */
public final class ConfigurationReleaseCodec implements ConfigurationReleaseConsumer.Decoder {

    private static final String EVENT_TYPE = "config.release.created";

    private final int maxPayloadBytes;
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    /**
     * 创建配置发布消息解码器。
     *
     * @param maxPayloadBytes 最大消息字节数
     */
    public ConfigurationReleaseCodec(int maxPayloadBytes) {
        if (maxPayloadBytes < 1) {
            throw new IllegalArgumentException("配置消息最大字节数必须大于 0");
        }
        this.maxPayloadBytes = maxPayloadBytes;
    }

    /**
     * 解码并验证事件类型、版本、关联标识和产品分区键。
     *
     * @param body 配置消息 JSON
     * @return 配置发布信封
     */
    @Override
    public MessageEnvelope<ConfigReleasePayload> decode(byte[] body) {
        if (body == null || body.length == 0 || body.length > maxPayloadBytes) {
            throw new InvalidConfigurationMessageException("配置消息大小不合法");
        }
        try {
            MessageEnvelope<ConfigReleasePayload> envelope = jsonMapper.readValue(
                    body, new TypeReference<>() { });
            ConfigReleasePayload payload = envelope.payload();
            if (!EVENT_TYPE.equals(envelope.eventType().value()) || envelope.schemaVersion() != 1) {
                throw new InvalidConfigurationMessageException("配置事件类型或结构版本不受支持");
            }
            if (!payload.releaseId().toString().equals(envelope.correlationId())
                    || !payload.productId().toString().equals(envelope.partitionKey())) {
                throw new InvalidConfigurationMessageException("配置信封关联标识与载荷不一致");
            }
            return envelope;
        } catch (InvalidConfigurationMessageException exception) {
            throw exception;
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new InvalidConfigurationMessageException("配置发布信封 JSON 无法解析", exception);
        }
    }
}
