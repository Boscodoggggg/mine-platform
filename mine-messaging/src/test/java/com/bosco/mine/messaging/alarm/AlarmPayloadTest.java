package com.bosco.mine.messaging.alarm;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 固定告警触发与恢复事件的完整自描述载荷契约。
 */
class AlarmPayloadTest {

    private static final UUID OCCURRENCE_ID =
            UUID.fromString("01980a3b-1200-7000-8000-000000000001");
    private static final UUID DEVICE_ID =
            UUID.fromString("01980a3b-1200-7000-8000-000000000002");
    private static final UUID SNAPSHOT_ID =
            UUID.fromString("01980a3b-1200-7000-8000-000000000003");
    private static final Instant RAISED_AT = Instant.parse("2026-07-17T03:00:00Z");

    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void 数值触发事件应完成稳定JSON往返() throws Exception {
        AlarmRaisedPayload payload = raisedPayload();

        AlarmRaisedPayload restored = jsonMapper.readValue(
                jsonMapper.writeValueAsBytes(payload), AlarmRaisedPayload.class);

        assertThat(restored).isEqualTo(payload);
        assertThat(restored.evidence().observedValue())
                .isEqualByComparingTo(new BigDecimal("96.5"));
    }

    @Test
    void 质量恢复事件应携带足够身份并完成稳定JSON往返() throws Exception {
        AlarmRecoveredPayload payload = new AlarmRecoveredPayload(
                OCCURRENCE_ID,
                DEVICE_ID,
                "excavator-v1",
                "rpm-required",
                "转速必填",
                "QUALITY",
                "WARNING",
                "engine.rpm",
                new AlarmEvidencePayload(
                        null, null, null, null, "REQUIRED_MISSING"),
                9,
                SNAPSHOT_ID,
                "sha256:" + "b".repeat(64),
                RAISED_AT,
                RAISED_AT.plusSeconds(90),
                "CONDITION_CLEARED"
        );

        AlarmRecoveredPayload restored = jsonMapper.readValue(
                jsonMapper.writeValueAsBytes(payload), AlarmRecoveredPayload.class);

        assertThat(restored).isEqualTo(payload);
        assertThat(restored.recoveredAt()).isAfter(restored.raisedAt());
        assertThat(restored.evidence().qualityRuleCode()).isEqualTo("REQUIRED_MISSING");
    }

    @Test
    void 应拒绝来源与证据字段错配() {
        assertThatThrownBy(() -> new AlarmRaisedPayload(
                OCCURRENCE_ID,
                DEVICE_ID,
                "excavator-v1",
                "temperature-high",
                "发动机高温",
                "NUMERIC",
                "CRITICAL",
                "engine.temperature",
                new AlarmEvidencePayload(
                        null, null, null, null, "ABOVE_MAXIMUM"),
                7,
                SNAPSHOT_ID,
                "sha256:" + "a".repeat(64),
                RAISED_AT
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("数值");
    }

    @Test
    void 应拒绝非UUIDv7周期和超长规则名称() {
        assertThatThrownBy(() -> new AlarmRaisedPayload(
                UUID.randomUUID(),
                DEVICE_ID,
                "excavator-v1",
                "temperature-high",
                "温".repeat(101),
                "NUMERIC",
                "CRITICAL",
                "engine.temperature",
                numericEvidence(),
                7,
                SNAPSHOT_ID,
                "sha256:" + "a".repeat(64),
                RAISED_AT
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UUIDv7");

        assertThatThrownBy(() -> new AlarmRaisedPayload(
                OCCURRENCE_ID,
                DEVICE_ID,
                "excavator-v1",
                "temperature-high",
                "温".repeat(101),
                "NUMERIC",
                "CRITICAL",
                "engine.temperature",
                numericEvidence(),
                7,
                SNAPSHOT_ID,
                "sha256:" + "a".repeat(64),
                RAISED_AT
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("名称");
    }

    @Test
    void 应拒绝恢复早于触发和未知恢复原因() {
        assertThatThrownBy(() -> new AlarmRecoveredPayload(
                OCCURRENCE_ID,
                DEVICE_ID,
                "excavator-v1",
                "temperature-high",
                "发动机高温",
                "NUMERIC",
                "CRITICAL",
                "engine.temperature",
                numericEvidence(),
                7,
                SNAPSHOT_ID,
                "sha256:" + "a".repeat(64),
                RAISED_AT,
                RAISED_AT.minusSeconds(1),
                "UNKNOWN"
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new AlarmRecoveredPayload(
                OCCURRENCE_ID,
                DEVICE_ID,
                "excavator-v1",
                "temperature-high",
                "发动机高温",
                "NUMERIC",
                "CRITICAL",
                "engine.temperature",
                numericEvidence(),
                7,
                SNAPSHOT_ID,
                "sha256:" + "a".repeat(64),
                RAISED_AT,
                RAISED_AT,
                "UNKNOWN"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("恢复原因");
    }

    private AlarmRaisedPayload raisedPayload() {
        return new AlarmRaisedPayload(
                OCCURRENCE_ID,
                DEVICE_ID,
                "excavator-v1",
                "temperature-high",
                "发动机高温",
                "NUMERIC",
                "CRITICAL",
                "engine.temperature",
                numericEvidence(),
                7,
                SNAPSHOT_ID,
                "sha256:" + "a".repeat(64),
                RAISED_AT
        );
    }

    private AlarmEvidencePayload numericEvidence() {
        return new AlarmEvidencePayload(
                new BigDecimal("96.5"),
                "GT",
                new BigDecimal("95"),
                new BigDecimal("90"),
                null
        );
    }
}
