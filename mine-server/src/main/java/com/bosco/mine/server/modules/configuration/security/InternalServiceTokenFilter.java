package com.bosco.mine.server.modules.configuration.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;

/**
 * 使用外部 secret 中的共享服务令牌保护内部配置分发接口。
 */
public final class InternalServiceTokenFilter extends OncePerRequestFilter {

    /** 运行节点发送服务令牌使用的请求头。 */
    public static final String TOKEN_HEADER = "X-Mine-Service-Token";
    private static final String CONFIGURATION_PREFIX = "/internal/v1/config-releases/";
    private static final String STRATEGY_PREFIX = "/internal/v1/strategy-runtime-snapshots/";
    private static final String AGGREGATION_RECOMPUTE_PREFIX =
            "/internal/v1/aggregation-recomputations/";

    private final ConfigurationDistributionProperties properties;

    /**
     * 创建内部服务令牌过滤器。
     *
     * @param properties 配置分发安全参数
     */
    public InternalServiceTokenFilter(ConfigurationDistributionProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith(CONFIGURATION_PREFIX)
                && !uri.startsWith(STRATEGY_PREFIX)
                && !uri.startsWith(AGGREGATION_RECOMPUTE_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!properties.resolvedEnabled()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String expected;
        try {
            expected = Files.readString(properties.resolvedServiceTokenFile()).strip();
            if (expected.getBytes(StandardCharsets.UTF_8).length < 32) {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                return;
            }
        } catch (IOException exception) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }
        String supplied = request.getHeader(TOKEN_HEADER);
        if (supplied == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
