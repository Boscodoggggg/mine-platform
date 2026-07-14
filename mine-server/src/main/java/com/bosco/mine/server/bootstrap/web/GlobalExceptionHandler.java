package com.bosco.mine.server.bootstrap.web;

import com.bosco.mine.common.error.MineException;
import com.bosco.mine.common.time.MineClock;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 将服务端异常转换为稳定且不泄露内部实现的 HTTP 错误契约。
 */
@RestControllerAdvice
public final class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String UNKNOWN_TRACE_ID = "unknown";

    private final MineClock clock = MineClock.systemUtc();

    /**
     * 处理平台已识别的业务或应用异常。
     *
     * @param exception 平台异常
     * @param request   当前 HTTP 请求
     * @return 参数错误响应
     */
    @ExceptionHandler(MineException.class)
    public ResponseEntity<ApiErrorResponse> handleMineException(
            MineException exception,
            HttpServletRequest request
    ) {
        ApiErrorResponse response = new ApiErrorResponse(
                exception.errorCode().code(),
                exception.errorCode().message(),
                traceIdOf(request),
                clock.instant(),
                exception.details()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * 兜底处理未识别异常，向调用方隐藏堆栈和敏感内部信息。
     *
     * @param exception 未识别异常
     * @param request   当前 HTTP 请求
     * @return 服务内部错误响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpectedException(
            Exception exception,
            HttpServletRequest request
    ) {
        String traceId = traceIdOf(request);
        LOGGER.error("未处理的服务端异常，traceId={}", traceId, exception);
        ApiErrorResponse response = new ApiErrorResponse(
                "INTERNAL_SERVER_ERROR",
                "服务暂时不可用",
                traceId,
                clock.instant(),
                Map.of()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    private String traceIdOf(HttpServletRequest request) {
        Object traceId = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return traceId instanceof String value && !value.isBlank() ? value : UNKNOWN_TRACE_ID;
    }
}
