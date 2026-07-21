package com.bosco.mine.server.modules.notification.service;

import cn.dev33.satoken.stp.StpUtil;
import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.server.modules.notification.adapter.NotificationChannelType;
import com.bosco.mine.server.modules.notification.dto.NotificationTemplateRequest;
import com.bosco.mine.server.modules.notification.dto.NotificationTemplateResponse;
import com.bosco.mine.server.modules.notification.model.NotificationTemplate;
import com.bosco.mine.server.modules.notification.repository.NotificationTemplateMapper;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 管理只允许固定占位符语法的安全通知模板。
 */
@Service
public class NotificationTemplateService {

    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-z][a-z0-9-]{1,63}$");
    private static final Pattern STATUS_PATTERN = Pattern.compile("ENABLED|DISABLED");
    private static final Pattern LOCALE_PATTERN = Pattern.compile("zh-CN|en-US");
    private static final Pattern PROVIDER_CODE_PATTERN =
            Pattern.compile("^[A-Za-z0-9_-]{1,128}$");
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile(
            "\\{\\{\\s*([a-z][a-zA-Z0-9]*(?:\\.[a-zA-Z][a-zA-Z0-9]*)*)\\s*}}"
    );
    private static final int MAX_PLACEHOLDERS = 128;

    private static final Set<String> SUPPORTED_VARIABLES = Set.of(
            "alarm.id",
            "alarm.ruleName",
            "alarm.severity",
            "alarm.occurredAt",
            "alarm.recoveredAt",
            "mine.id",
            "mine.name",
            "device.id",
            "device.code",
            "device.name",
            "point.id",
            "point.name",
            "point.value",
            "ticket.id",
            "ticket.number",
            "ticket.status",
            "escalation.step",
            "recipient.displayName"
    );

    private static final ErrorCode TEMPLATE_NOT_FOUND =
            new ErrorCode("NOTIFICATION-404-021", "通知模板不存在");
    private static final ErrorCode TEMPLATE_CONFLICT =
            new ErrorCode("NOTIFICATION-409-021", "通知模板已被其他管理员修改，请刷新后重试");
    private static final ErrorCode TEMPLATE_DUPLICATE =
            new ErrorCode("NOTIFICATION-409-022", "通知模板编码已经存在");
    private static final ErrorCode TEMPLATE_INVALID =
            new ErrorCode("NOTIFICATION-400-021", "通知模板配置不合法");

    private final NotificationTemplateMapper mapper;
    private final OperationAuditService auditService;
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator = IdGenerator.timeOrdered();

    /**
     * 创建安全通知模板管理服务。
     *
     * @param mapper 通知模板数据接口
     * @param auditService 操作审计服务
     * @param objectMapper JSON 映射器
     */
    public NotificationTemplateService(
            NotificationTemplateMapper mapper,
            OperationAuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.mapper = mapper;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询全部通知模板。
     *
     * @return 通知模板列表
     */
    @Transactional(readOnly = true)
    public List<NotificationTemplateResponse> list() {
        return mapper.findAll().stream().map(this::toResponse).toList();
    }

    /**
     * 查询指定通知模板。
     *
     * @param templateId 模板标识
     * @return 通知模板
     */
    @Transactional(readOnly = true)
    public NotificationTemplateResponse get(UUID templateId) {
        return toResponse(requireTemplate(templateId));
    }

    /**
     * 创建经过安全语法校验的通知模板。
     *
     * @param request 创建请求
     * @return 创建后的模板
     */
    @Transactional
    public NotificationTemplateResponse create(NotificationTemplateRequest request) {
        if (mapper.findByCode(request.templateCode()).isPresent()) {
            throw duplicate(request.templateCode());
        }
        NormalizedTemplate normalized = normalize(request);
        Instant now = Instant.now();
        NotificationTemplate template = new NotificationTemplate(
                idGenerator.next(), request.templateCode(), normalized.name(),
                request.channelType(), request.eventType(), request.locale(),
                normalized.subject(), normalized.body(), normalized.providerTemplateCode(),
                writeVariables(normalized.variables()), request.status(), now, now, 0);
        try {
            if (mapper.insert(template, currentOperatorId()) != 1) {
                throw new IllegalStateException("通知模板写入行数异常");
            }
        } catch (DuplicateKeyException exception) {
            throw duplicate(request.templateCode());
        }
        auditService.recordSuccess(
                "NOTIFICATION", "CREATE_NOTIFICATION_TEMPLATE",
                "notification:template:edit", "NOTIFICATION_TEMPLATE",
                template.id(), Map.of(), summary(template, normalized.variables()));
        return toResponse(template);
    }

    /**
     * 使用乐观锁修改通知模板。
     *
     * @param templateId 模板标识
     * @param request 修改请求
     * @return 修改后的模板
     */
    @Transactional
    public NotificationTemplateResponse update(
            UUID templateId,
            NotificationTemplateRequest request
    ) {
        NotificationTemplate existing = requireTemplate(templateId);
        if (!existing.templateCode().equals(request.templateCode())) {
            throw invalid("TEMPLATE_CODE_IMMUTABLE");
        }
        if (existing.version() != request.version()) {
            throw conflict(templateId);
        }
        NormalizedTemplate normalized = normalize(request);
        NotificationTemplate candidate = new NotificationTemplate(
                templateId, existing.templateCode(), normalized.name(),
                request.channelType(), request.eventType(), request.locale(),
                normalized.subject(), normalized.body(), normalized.providerTemplateCode(),
                writeVariables(normalized.variables()), request.status(),
                existing.createdAt(), Instant.now(), existing.version() + 1);
        if (mapper.update(candidate, request.version(), currentOperatorId()) != 1) {
            throw conflict(templateId);
        }
        auditService.recordSuccess(
                "NOTIFICATION", "UPDATE_NOTIFICATION_TEMPLATE",
                "notification:template:edit", "NOTIFICATION_TEMPLATE",
                templateId, summary(existing, readVariables(existing)),
                summary(candidate, normalized.variables()));
        return toResponse(candidate);
    }

    /**
     * 返回平台模板允许声明的固定变量目录。
     *
     * @return 按字典序排列的变量目录
     */
    public List<String> supportedVariables() {
        return SUPPORTED_VARIABLES.stream().sorted().toList();
    }

    private NormalizedTemplate normalize(NotificationTemplateRequest request) {
        if (request == null || request.templateCode() == null
                || !CODE_PATTERN.matcher(request.templateCode()).matches()
                || request.name() == null || request.name().isBlank()
                || request.name().length() > 128
                || request.channelType() == null || request.eventType() == null
                || request.locale() == null
                || !LOCALE_PATTERN.matcher(request.locale()).matches()
                || request.bodyTemplate() == null || request.bodyTemplate().isBlank()
                || request.bodyTemplate().length() > 16384
                || request.status() == null
                || !STATUS_PATTERN.matcher(request.status()).matches()
                || request.allowedVariables() == null
                || request.allowedVariables().size() > 64) {
            throw invalid("TEMPLATE_INVALID");
        }
        String subject = normalizeNullable(request.subjectTemplate());
        if (subject != null && (subject.length() > 1000
                || subject.indexOf('\r') >= 0 || subject.indexOf('\n') >= 0)) {
            throw invalid("SUBJECT_INVALID");
        }
        if (request.channelType() == NotificationChannelType.EMAIL
                && "ENABLED".equals(request.status()) && subject == null) {
            throw invalid("EMAIL_SUBJECT_REQUIRED");
        }
        String providerTemplateCode = normalizeNullable(request.providerTemplateCode());
        if (providerTemplateCode != null
                && !PROVIDER_CODE_PATTERN.matcher(providerTemplateCode).matches()) {
            throw invalid("PROVIDER_TEMPLATE_CODE_INVALID");
        }
        if ((request.channelType() == NotificationChannelType.SMS
                || request.channelType() == NotificationChannelType.VOICE)
                && "ENABLED".equals(request.status()) && providerTemplateCode == null) {
            throw invalid("PROVIDER_TEMPLATE_CODE_REQUIRED");
        }
        TreeSet<String> variables = new TreeSet<>();
        for (String variable : request.allowedVariables()) {
            if (variable == null || !SUPPORTED_VARIABLES.contains(variable)) {
                throw invalid("VARIABLE_UNSUPPORTED");
            }
            variables.add(variable);
        }
        validateTemplateSyntax(subject, variables);
        String body = request.bodyTemplate().strip();
        validateTemplateSyntax(body, variables);
        return new NormalizedTemplate(
                request.name().strip(), subject, body, providerTemplateCode,
                List.copyOf(variables));
    }

    private void validateTemplateSyntax(String content, Set<String> allowedVariables) {
        if (content == null) {
            return;
        }
        if (content.contains("${") || content.contains("#{")
                || content.contains("{%") || content.contains("%}")
                || content.toLowerCase(java.util.Locale.ROOT).contains("<script")) {
            throw invalid("EXPRESSION_NOT_ALLOWED");
        }
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(content);
        int count = 0;
        while (matcher.find()) {
            count++;
            if (count > MAX_PLACEHOLDERS) {
                throw invalid("PLACEHOLDER_LIMIT_EXCEEDED");
            }
            if (!allowedVariables.contains(matcher.group(1))) {
                throw invalid("VARIABLE_NOT_ALLOWED");
            }
        }
        String remainder = PLACEHOLDER_PATTERN.matcher(content).replaceAll("");
        if (remainder.contains("{{") || remainder.contains("}}")) {
            throw invalid("PLACEHOLDER_INVALID");
        }
    }

    private NotificationTemplate requireTemplate(UUID templateId) {
        return mapper.findById(templateId).orElseThrow(() -> new MineException(
                TEMPLATE_NOT_FOUND, Map.of("templateId", templateId)));
    }

    private NotificationTemplateResponse toResponse(NotificationTemplate template) {
        return new NotificationTemplateResponse(
                template.id(), template.templateCode(), template.name(),
                template.channelType(), template.eventType(), template.locale(),
                template.subjectTemplate(), template.bodyTemplate(),
                template.providerTemplateCode(), readVariables(template), template.status(),
                template.createdAt(), template.updatedAt(), template.version());
    }

    private String writeVariables(List<String> variables) {
        try {
            return objectMapper.writeValueAsString(variables);
        } catch (JacksonException exception) {
            throw new IllegalStateException("通知模板变量白名单序列化失败", exception);
        }
    }

    private List<String> readVariables(NotificationTemplate template) {
        try {
            List<String> variables = objectMapper.readValue(
                    template.allowedVariablesJson(), new TypeReference<>() { });
            return List.copyOf(variables);
        } catch (JacksonException | NullPointerException exception) {
            throw new IllegalStateException("通知模板变量白名单结构无效", exception);
        }
    }

    private Map<String, Object> summary(
            NotificationTemplate template,
            List<String> variables
    ) {
        return Map.of(
                "templateCode", template.templateCode(),
                "channelType", template.channelType().name(),
                "eventType", template.eventType().name(),
                "locale", template.locale(),
                "providerTemplateConfigured", template.providerTemplateCode() != null,
                "variables", variables,
                "status", template.status(),
                "version", template.version());
    }

    private String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private MineException conflict(UUID templateId) {
        return new MineException(TEMPLATE_CONFLICT, Map.of("templateId", templateId));
    }

    private MineException duplicate(String templateCode) {
        return new MineException(TEMPLATE_DUPLICATE, Map.of("templateCode", templateCode));
    }

    private MineException invalid(String reason) {
        return new MineException(TEMPLATE_INVALID, Map.of("reason", reason));
    }

    private UUID currentOperatorId() {
        try {
            return UUID.fromString(StpUtil.getLoginIdAsString());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * 经过安全校验和标准化的模板字段集合。
     */
    private record NormalizedTemplate(
            String name,
            String subject,
            String body,
            String providerTemplateCode,
            List<String> variables
    ) {
    }
}
