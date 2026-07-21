package com.bosco.mine.server.modules.system.service;

import cn.dev33.satoken.stp.StpUtil;
import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.server.bootstrap.web.TraceIdFilter;
import com.bosco.mine.server.modules.system.repository.AccessManagementMapper;
import com.bosco.mine.server.modules.system.repository.SystemAuthMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 统一生成访问管理写操作的脱敏审计记录。
 */
@Service
public class OperationAuditService {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    private final AccessManagementMapper accessMapper;
    private final SystemAuthMapper authMapper;
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator = IdGenerator.timeOrdered();

    /**
     * 创建操作审计服务。
     *
     * @param accessMapper 访问管理数据接口
     * @param authMapper   认证数据接口
     * @param objectMapper JSON 序列化器
     */
    public OperationAuditService(
            AccessManagementMapper accessMapper,
            SystemAuthMapper authMapper,
            ObjectMapper objectMapper
    ) {
        this.accessMapper = accessMapper;
        this.authMapper = authMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 追加一条成功操作记录，摘要中不得传入密码、散列或令牌。
     *
     * @param action        操作类型
     * @param permission    所需权限码
     * @param resourceType  资源类型
     * @param resourceId    资源标识
     * @param beforeSummary 操作前脱敏摘要
     * @param afterSummary  操作后脱敏摘要
     */
    public void recordSuccess(
            String action,
            String permission,
            String resourceType,
            UUID resourceId,
            Map<String, ?> beforeSummary,
            Map<String, ?> afterSummary
    ) {
        recordSuccess(
                "SYSTEM_ACCESS",
                action,
                permission,
                resourceType,
                resourceId,
                beforeSummary,
                afterSummary
        );
    }

    /**
     * 追加指定业务模块的一条成功操作记录，摘要中不得传入密码、散列或令牌。
     *
     * @param module        业务模块编码
     * @param action        操作类型
     * @param permission    所需权限码
     * @param resourceType  资源类型
     * @param resourceId    资源标识
     * @param beforeSummary 操作前脱敏摘要
     * @param afterSummary  操作后脱敏摘要
     */
    public void recordSuccess(
            String module,
            String action,
            String permission,
            String resourceType,
            UUID resourceId,
            Map<String, ?> beforeSummary,
            Map<String, ?> afterSummary
    ) {
        HttpServletRequest request = currentRequest();
        String traceId = traceId(request);
        UUID operatorId = currentOperatorId();
        String username = operatorId == null
                ? null
                : authMapper.findUserById(operatorId).map(SystemAuthMapper.AuthUserRecord::username).orElse(null);
        accessMapper.insertOperationLog(
                idGenerator.next(),
                operatorId,
                username,
                module,
                action,
                permission,
                resourceType,
                resourceId.toString(),
                requestId(request, traceId),
                traceId,
                request == null ? null : request.getRemoteAddr(),
                request == null ? null : truncate(request.getHeader("User-Agent"), 512),
                toJson(beforeSummary),
                toJson(afterSummary)
        );
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    private String traceId(HttpServletRequest request) {
        if (request != null) {
            Object value = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
            if (value instanceof String traceId && !traceId.isBlank()) {
                return truncate(traceId, 64);
            }
        }
        return UUID.randomUUID().toString();
    }

    private String requestId(HttpServletRequest request, String traceId) {
        if (request == null) {
            return traceId;
        }
        String value = request.getHeader(REQUEST_ID_HEADER);
        return value != null && SAFE_REQUEST_ID.matcher(value).matches() ? value : traceId;
    }

    private UUID currentOperatorId() {
        try {
            return UUID.fromString(StpUtil.getLoginIdAsString());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String toJson(Map<String, ?> summary) {
        if (summary == null || summary.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (JacksonException exception) {
            throw new IllegalStateException("操作审计摘要序列化失败", exception);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
