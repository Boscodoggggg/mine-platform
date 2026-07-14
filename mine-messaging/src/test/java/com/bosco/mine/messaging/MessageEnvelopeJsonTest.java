package com.bosco.mine.messaging;

import com.bosco.mine.model.message.UnifiedDeviceMessage;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验统一消息信封 v1 的 JSON 稳定契约和向后兼容行为。
 */
class MessageEnvelopeJsonTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void 应按黄金契约完成JSON往返() throws IOException {
        String fixture = Files.readString(contractPath());

        MessageEnvelope<UnifiedDeviceMessage> envelope = jsonMapper.readValue(
                fixture, new TypeReference<>() { });

        assertThat(envelope.eventId().version()).isEqualTo(7);
        assertThat(envelope.eventType()).isEqualTo(MessageType.of("telemetry.normalized"));
        assertThat(envelope.schemaVersion()).isEqualTo(1);
        assertThat(envelope.occurredAt()).isEqualTo(Instant.parse("2026-07-14T08:00:00Z"));
        assertThat(envelope.payload().deviceId()).isEqualTo("device-001");

        JsonNode expected = jsonMapper.readTree(fixture);
        JsonNode actual = jsonMapper.readTree(jsonMapper.writeValueAsString(envelope));
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void 应忽略新版本增加的未知可选字段() throws IOException {
        String compatibleJson = Files.readString(contractPath())
                .replace("\"schemaVersion\": 1,", "\"schemaVersion\": 1,\n  \"futureEnvelopeField\": true,")
                .replace("\"messageType\": \"TELEMETRY\",",
                        "\"messageType\": \"TELEMETRY\",\n    \"futureDeviceField\": \"ignored\",");

        MessageEnvelope<UnifiedDeviceMessage> envelope = jsonMapper.readValue(
                compatibleJson, new TypeReference<>() { });

        assertThat(envelope.payload().deviceId()).isEqualTo("device-001");
    }

    @Test
    void 应拒绝缺少必填字段的信封() {
        assertThatThrownBy(() -> new MessageEnvelope<>(
                null,
                MessageType.of("telemetry.normalized"),
                1,
                Instant.parse("2026-07-14T08:00:00Z"),
                Instant.parse("2026-07-14T08:00:02Z"),
                "mine-gateway",
                "trace-001",
                null,
                "device-001",
                "mine-local",
                "payload"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("eventId");
    }

    @Test
    void 应拒绝非UUIDv7事件标识() {
        assertThatThrownBy(() -> new MessageEnvelope<>(
                UUID.randomUUID(),
                MessageType.of("telemetry.normalized"),
                1,
                Instant.parse("2026-07-14T08:00:00Z"),
                Instant.parse("2026-07-14T08:00:02Z"),
                "mine-gateway",
                "trace-001",
                null,
                "device-001",
                "mine-local",
                "payload"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UUIDv7");
    }

    @Test
    void 应拒绝空白事件类型() {
        assertThatThrownBy(() -> MessageType.of(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType");
    }

    private Path contractPath() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("docs/contracts/message-envelope-v1.json");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("找不到 docs/contracts/message-envelope-v1.json");
    }
}
