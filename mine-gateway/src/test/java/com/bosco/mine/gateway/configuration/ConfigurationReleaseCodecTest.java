package com.bosco.mine.gateway.configuration;

import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.MessageType;
import com.bosco.mine.messaging.configuration.ConfigReleasePayload;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验配置发布消息信封的类型、版本、关联标识和大小边界。
 */
class ConfigurationReleaseCodecTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void 应解码字段一致的配置发布信封() throws Exception {
        ConfigurationReleaseCodec codec = new ConfigurationReleaseCodec(4096);
        MessageEnvelope<ConfigReleasePayload> source = envelope("config.release.created", 1);

        MessageEnvelope<ConfigReleasePayload> decoded = codec.decode(
                jsonMapper.writeValueAsBytes(source));

        assertThat(decoded.payload()).isEqualTo(source.payload());
        assertThat(decoded.correlationId()).isEqualTo(source.payload().releaseId().toString());
    }

    @Test
    void 错误事件类型和超限消息必须拒绝() throws Exception {
        ConfigurationReleaseCodec codec = new ConfigurationReleaseCodec(512);

        assertThatThrownBy(() -> codec.decode(jsonMapper.writeValueAsBytes(
                envelope("config.release.unknown", 1))))
                .isInstanceOf(InvalidConfigurationMessageException.class);
        assertThatThrownBy(() -> codec.decode(new byte[513]))
                .isInstanceOf(InvalidConfigurationMessageException.class)
                .hasMessageContaining("大小");
    }

    private MessageEnvelope<ConfigReleasePayload> envelope(String eventType, int schemaVersion)
            throws JacksonException {
        UUID releaseId = UUID.fromString("019f6a12-a000-7000-8000-000000000010");
        UUID productId = UUID.fromString("019f6a12-a000-7000-8000-000000000020");
        ConfigReleasePayload payload = new ConfigReleasePayload(
                releaseId,
                UUID.fromString("019f6a12-a000-7000-8000-000000000030"),
                productId,
                7,
                "sha256:" + "a".repeat(64)
        );
        return new MessageEnvelope<>(
                UUID.fromString("019f6a12-a000-7000-8000-000000000001"),
                MessageType.of(eventType), schemaVersion,
                Instant.parse("2026-07-16T08:00:00Z"),
                Instant.parse("2026-07-16T08:00:01Z"),
                "mine-server", "trace-config", releaseId.toString(),
                productId.toString(), "mine-local", payload
        );
    }
}
