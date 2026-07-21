package com.bosco.mine.server.bootstrap.web;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 面向管理端和第三方调用方的统一 HTTP 错误响应。
 *
 * @param code      稳定错误码
 * @param message   可读错误消息
 * @param traceId   本次请求链路标识
 * @param timestamp 错误发生时刻
 * @param details   可安全返回给调用方的结构化详情对象；字段错误放入 violations 数组
 */
public record ApiErrorResponse(
        String code,
        String message,
        String traceId,
        Instant timestamp,
        Map<String, Object> details
) {

    /**
     * 校验错误响应字段并冻结详情集合。
     */
    public ApiErrorResponse {
        Objects.requireNonNull(code, "错误码不能为空");
        Objects.requireNonNull(message, "错误消息不能为空");
        Objects.requireNonNull(traceId, "链路标识不能为空");
        Objects.requireNonNull(timestamp, "错误时间不能为空");
        details = Map.copyOf(Objects.requireNonNull(details, "错误详情不能为空"));
    }
}
