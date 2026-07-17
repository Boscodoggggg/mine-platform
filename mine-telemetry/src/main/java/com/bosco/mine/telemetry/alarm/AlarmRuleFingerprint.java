package com.bosco.mine.telemetry.alarm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 为已编译告警规则生成不依赖 JSON 字段顺序的稳定语义指纹。
 */
public final class AlarmRuleFingerprint {

    private AlarmRuleFingerprint() {
    }

    /**
     * 计算规则全部运行语义的 SHA-256 指纹。
     *
     * @param rule 已编译规则
     * @return 带算法前缀的稳定指纹
     */
    public static String sha256(AlarmPolicy.Rule rule) {
        Objects.requireNonNull(rule, "告警规则不能为空");
        String canonical = String.join("\n",
                rule.key(), rule.sourceType().name(), rule.severity().name(),
                Integer.toString(rule.triggerDurationSeconds()),
                Integer.toString(rule.recoverDurationSeconds()),
                Integer.toString(rule.cooldownSeconds()), Boolean.toString(rule.enabled()),
                nullable(rule.propertyId()), nullable(rule.operator()), nullable(rule.threshold()),
                nullable(rule.recoverThreshold()), nullable(rule.qualityRuleCode()));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(
                    digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        }
    }

    private static String nullable(Object value) {
        return value == null ? "<null>" : value.toString();
    }
}
