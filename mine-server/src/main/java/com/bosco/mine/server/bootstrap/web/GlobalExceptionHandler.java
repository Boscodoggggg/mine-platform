package com.bosco.mine.server.bootstrap.web;

import com.bosco.mine.common.error.MineException;
import com.bosco.mine.common.time.MineClock;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将服务端异常转换为稳定且不泄露内部实现的 HTTP 错误契约。
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String UNKNOWN_TRACE_ID = "unknown";
    private static final Pattern ERROR_CODE_STATUS = Pattern.compile(
            "^[A-Z][A-Z0-9_]*-(?<status>[45]\\d{2})-\\d+$"
    );

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
        HttpStatus status = statusOf(exception);
        boolean serverError = status.is5xxServerError();
        if (serverError) {
            LOGGER.error("平台异常映射为服务端错误，traceId={}，code={}",
                    traceIdOf(request), exception.errorCode().code(), exception);
        }
        ApiErrorResponse response = new ApiErrorResponse(
                exception.errorCode().code(),
                serverError ? "服务暂时不可用" : exception.errorCode().message(),
                traceIdOf(request),
                clock.instant(),
                serverError ? Map.of() : exception.details()
        );
        return ResponseEntity.status(status).body(response);
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

    /**
     * 将 Spring MVC 已识别的请求异常转换为平台错误结构，同时保留框架计算的 HTTP 状态。
     *
     * @param exception 请求处理异常
     * @param body      框架默认响应体，本实现不会直接返回
     * @param headers   框架计算的响应头
     * @param status    框架计算的 HTTP 状态
     * @param request   当前 Web 请求
     * @return 平台统一错误响应
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception,
            Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        HttpServletRequest servletRequest = request instanceof ServletWebRequest servletWebRequest
                ? servletWebRequest.getRequest()
                : null;
        String traceId = traceIdOf(servletRequest);
        if (status.is5xxServerError()) {
            LOGGER.error("Spring MVC 请求处理失败，traceId={}，status={}", traceId, status.value(), exception);
        }
        ApiErrorResponse response = new ApiErrorResponse(
                status.is4xxClientError() ? "REQUEST-" + status.value() + "-001" : "INTERNAL_SERVER_ERROR",
                clientMessage(status),
                traceId,
                clock.instant(),
                Map.of()
        );
        return new ResponseEntity<>(response, headers, status);
    }

    private HttpStatus statusOf(MineException exception) {
        Matcher matcher = ERROR_CODE_STATUS.matcher(exception.errorCode().code());
        if (!matcher.matches()) {
            return HttpStatus.BAD_REQUEST;
        }
        HttpStatus status = HttpStatus.resolve(Integer.parseInt(matcher.group("status")));
        return status == null ? HttpStatus.BAD_REQUEST : status;
    }

    private String clientMessage(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "请求内容不合法";
            case 401 -> "登录状态无效";
            case 403 -> "没有操作权限";
            case 404 -> "请求资源不存在";
            case 405 -> "请求方法不支持";
            case 409 -> "请求与当前状态冲突";
            case 415 -> "请求媒体类型不支持";
            case 422 -> "请求内容无法处理";
            default -> status.is4xxClientError() ? "请求处理失败" : "服务暂时不可用";
        };
    }

    private String traceIdOf(HttpServletRequest request) {
        if (request == null) {
            return UNKNOWN_TRACE_ID;
        }
        Object traceId = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return traceId instanceof String value && !value.isBlank() ? value : UNKNOWN_TRACE_ID;
    }
}
