package com.bosco.mine.simulator.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 将配置校验和运行错误转换为页面可读的标准错误响应。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * 处理请求参数和运行状态错误。
     *
     * @param exception 业务异常
     * @return 标准错误内容
     */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ProblemDetail handleBusinessError(RuntimeException exception) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        detail.setTitle("操作失败");
        detail.setDetail(exception.getMessage());
        return detail;
    }

    /**
     * 处理无法解析的 JSON 请求。
     *
     * @param exception 解析异常
     * @return 标准错误内容
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadable(HttpMessageNotReadableException exception) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        detail.setTitle("配置格式错误");
        Throwable cause = exception.getMostSpecificCause();
        detail.setDetail(cause == null ? exception.getMessage() : cause.getMessage());
        return detail;
    }
}
