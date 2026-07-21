package com.bosco.mine.server.modules.notification.adapter;

import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 通知适配器返回的稳定投递结果。
 *
 * @param outcome           投递结果类型
 * @param providerMessageId 供应商消息标识
 * @param errorCode         固定低基数错误码
 * @param retryAfter        供应商明确建议的最早重试时间
 */
public record NotificationDeliveryResult(
        Outcome outcome,
        String providerMessageId,
        String errorCode,
        Instant retryAfter
) {
    private static final Pattern PROVIDER_MESSAGE_ID =
            Pattern.compile("^[A-Za-z0-9._:/+^=-]{1,256}$");
    private static final Pattern ERROR_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,63}$");

    /**
     * 校验不同结果类型的字段组合。
     */
    public NotificationDeliveryResult {
        if (outcome == null) {
            throw new IllegalArgumentException("通知投递结果不能为空");
        }
        if (providerMessageId != null && !PROVIDER_MESSAGE_ID.matcher(providerMessageId).matches()) {
            throw new IllegalArgumentException("供应商消息标识格式不合法");
        }
        if (errorCode != null && !ERROR_CODE.matcher(errorCode).matches()) {
            throw new IllegalArgumentException("通知适配器错误码格式不合法");
        }
        boolean failure = outcome == Outcome.RETRYABLE_FAILURE
                || outcome == Outcome.PERMANENT_FAILURE || outcome == Outcome.UNKNOWN;
        if (failure != (errorCode != null)) {
            throw new IllegalArgumentException("通知投递结果与错误码组合不合法");
        }
        if (retryAfter != null && outcome != Outcome.RETRYABLE_FAILURE) {
            throw new IllegalArgumentException("只有可重试失败允许携带重试时间");
        }
    }

    /** 创建供应商已经受理但尚未证明终端送达的结果。 */
    public static NotificationDeliveryResult submitted(String providerMessageId) {
        return new NotificationDeliveryResult(Outcome.SUBMITTED, providerMessageId, null, null);
    }

    /** 创建已经获得终端级确认的结果。 */
    public static NotificationDeliveryResult delivered(String providerMessageId) {
        return new NotificationDeliveryResult(Outcome.DELIVERED, providerMessageId, null, null);
    }

    /** 创建允许由工作器有界重试的失败结果。 */
    public static NotificationDeliveryResult retryableFailure(String errorCode, Instant retryAt) {
        return new NotificationDeliveryResult(Outcome.RETRYABLE_FAILURE, null, errorCode, retryAt);
    }

    /** 创建不会因相同请求而恢复的永久失败结果。 */
    public static NotificationDeliveryResult permanentFailure(String errorCode) {
        return new NotificationDeliveryResult(Outcome.PERMANENT_FAILURE, null, errorCode, null);
    }

    /** 创建无法判断供应商是否已受理且禁止盲目重试的结果。 */
    public static NotificationDeliveryResult unknown(String errorCode) {
        return new NotificationDeliveryResult(Outcome.UNKNOWN, null, errorCode, null);
    }

    /** 返回可选的最早重试时间。 */
    public Optional<Instant> retryAt() {
        return Optional.ofNullable(retryAfter);
    }

    /**
     * 适配器层稳定结果枚举。
     */
    public enum Outcome {
        /** 供应商受理，尚未证明终端送达。 */
        SUBMITTED,
        /** 已获得终端级送达确认。 */
        DELIVERED,
        /** 可安全进行有界重试。 */
        RETRYABLE_FAILURE,
        /** 相同请求重试不会恢复。 */
        PERMANENT_FAILURE,
        /** 无法判断供应商是否已受理。 */
        UNKNOWN
    }
}
