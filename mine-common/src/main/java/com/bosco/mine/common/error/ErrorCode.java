package com.bosco.mine.common.error;

import java.util.Objects;

/**
 * 表示可跨接口、日志和消息稳定传递的平台错误定义。
 *
 * @param code    不随展示文案变化的稳定错误码
 * @param message 面向调用方的默认中文消息
 */
public record ErrorCode(String code, String message) {

    /**
     * 创建并校验错误定义。
     */
    public ErrorCode {
        Objects.requireNonNull(code, "错误码不能为空");
        Objects.requireNonNull(message, "错误消息不能为空");
        if (code.isBlank()) {
            throw new IllegalArgumentException("错误码不能为空白字符串");
        }
    }
}
