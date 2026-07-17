package com.bosco.mine.telemetry.alarm;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验规则指纹只跟踪运行语义，展示名称变化不得关闭活动告警周期。
 */
class AlarmRuleFingerprintTest {

    @Test
    void 展示名称不应改变指纹但等级变化必须改变指纹() {
        AlarmPolicy.Rule original = rule("发动机高温", AlarmPolicy.Severity.WARNING);
        AlarmPolicy.Rule renamed = rule("发动机温度过高", AlarmPolicy.Severity.WARNING);
        AlarmPolicy.Rule escalated = rule("发动机高温", AlarmPolicy.Severity.CRITICAL);

        assertThat(AlarmRuleFingerprint.sha256(renamed))
                .isEqualTo(AlarmRuleFingerprint.sha256(original));
        assertThat(AlarmRuleFingerprint.sha256(escalated))
                .isNotEqualTo(AlarmRuleFingerprint.sha256(original));
    }

    private AlarmPolicy.Rule rule(String name, AlarmPolicy.Severity severity) {
        return new AlarmPolicy.Rule(
                "engine-temperature-high", name,
                AlarmPolicy.SourceType.NUMERIC, severity,
                0, 0, 60, true, "engine.temperature",
                AlarmPolicy.Operator.GT, new BigDecimal("95"),
                new BigDecimal("90"), null);
    }
}
