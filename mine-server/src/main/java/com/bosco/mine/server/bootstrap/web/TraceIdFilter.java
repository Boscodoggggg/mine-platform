package com.bosco.mine.server.bootstrap.web;

import com.bosco.mine.common.id.IdGenerator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * 为每个 HTTP 请求建立可跨响应与日志定位的链路标识。
 */
@Component
public final class TraceIdFilter extends OncePerRequestFilter {

    /** HTTP 链路标识请求头和响应头名称。 */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /** 请求对象中保存链路标识的属性名称。 */
    public static final String TRACE_ID_ATTRIBUTE = TraceIdFilter.class.getName() + ".traceId";

    private static final String MDC_KEY = "traceId";
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    private final IdGenerator idGenerator = IdGenerator.timeOrdered();

    /**
     * 创建 HTTP 链路标识过滤器。
     */
    public TraceIdFilter() {
    }

    /**
     * 复用合法的上游链路标识，缺失或不合法时生成 UUIDv7 标识。
     *
     * @param request     HTTP 请求
     * @param response    HTTP 响应
     * @param filterChain Servlet 过滤器链
     * @throws ServletException 下游 Servlet 处理失败
     * @throws IOException      HTTP 输入输出失败
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String traceId = resolveTraceId(request.getHeader(TRACE_ID_HEADER));
        request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);

        try (MDC.MDCCloseable ignored = MDC.putCloseable(MDC_KEY, traceId)) {
            filterChain.doFilter(request, response);
        }
    }

    private String resolveTraceId(String candidate) {
        if (candidate != null && SAFE_TRACE_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return idGenerator.next().toString();
    }
}
