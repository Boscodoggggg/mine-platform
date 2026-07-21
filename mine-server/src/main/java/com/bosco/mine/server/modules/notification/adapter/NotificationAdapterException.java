package com.bosco.mine.server.modules.notification.adapter;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 适配器无法用正常结果返回时使用的固定分类异常。
 */
public final class NotificationAdapterException extends RuntimeException {
    private static final Pattern ERROR_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,63}$");

    private final FailureKind failureKind;
    private final String errorCode;

    /**
     * 创建不包含敏感请求内容的适配器异常。
     *
     * @param failureKind 稳定失败分类
     * @param errorCode   固定低基数错误码
     * @param message     脱敏诊断信息
     */
    public NotificationAdapterException(
            FailureKind failureKind,
            String errorCode,
            String message
    ) {
        super(message);
        this.failureKind = Objects.requireNonNull(failureKind, "通知失败分类不能为空");
        if (errorCode == null || !ERROR_CODE.matcher(errorCode).matches()) {
            throw new IllegalArgumentException("通知适配器错误码格式不合法");
        }
        this.errorCode = errorCode;
    }

    /** 返回稳定失败分类。 */
    public FailureKind failureKind() {
        return failureKind;
    }

    /** 返回固定低基数错误码。 */
    public String errorCode() {
        return errorCode;
    }

    /**
     * 适配器异常稳定分类。
     */
    public enum FailureKind {
        /** 当前请求稍后可以安全重试。 */
        RETRYABLE,
        /** 相同请求重试不会恢复。 */
        PERMANENT,
        /** 无法判断供应商是否已受理。 */
        UNKNOWN
    }
}
