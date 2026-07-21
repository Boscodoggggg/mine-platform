package com.bosco.mine.server.modules.command.template;

import cn.dev33.satoken.stp.StpUtil;
import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.server.modules.command.template.CommandTemplateMapper.TemplateDraft;
import com.bosco.mine.server.modules.command.template.CommandTemplateMapper.TemplateRecord;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 管理低风险命令模板草稿、产品绑定和不可变发布版本。
 */
@Service
public class CommandTemplateService {
    private static final ErrorCode TEMPLATE_NOT_FOUND =
            new ErrorCode("COMMAND-TEMPLATE-404-001", "命令模板不存在");
    private static final ErrorCode TEMPLATE_DUPLICATE =
            new ErrorCode("COMMAND-TEMPLATE-409-001", "命令模板编码已经存在");
    private static final ErrorCode TEMPLATE_CONFLICT =
            new ErrorCode("COMMAND-TEMPLATE-409-002", "模板已被其他管理员修改，请刷新后重试");
    private static final ErrorCode DRAFT_REQUIRED =
            new ErrorCode("COMMAND-TEMPLATE-422-001", "只有模板草稿允许修改或发布");
    private static final ErrorCode TEMPLATE_INVALID =
            new ErrorCode("COMMAND-TEMPLATE-422-002", "命令模板参数或载荷不合法");
    private static final ErrorCode PRODUCT_INVALID =
            new ErrorCode("COMMAND-TEMPLATE-422-003", "命令模板绑定的产品不存在或已归档");
    private static final ErrorCode REVISION_SOURCE_INVALID =
            new ErrorCode("COMMAND-TEMPLATE-422-004", "只有已发布模板允许复制为下一版草稿");

    private static final Pattern TEMPLATE_KEY = Pattern.compile("^[A-Z][A-Z0-9_]{2,127}$");
    private static final Set<String> SCHEMA_ROOT_KEYS =
            Set.of("type", "additionalProperties", "properties", "required");
    private static final Set<String> PROPERTY_KEYS =
            Set.of("type", "titleKey", "minimum", "maximum", "enum");
    private static final Set<String> PROPERTY_TYPES =
            Set.of("string", "number", "integer", "boolean");

    private final CommandTemplateMapper mapper;
    private final OperationAuditService auditService;
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator = IdGenerator.timeOrdered();

    /**
     * 创建命令模板服务。
     *
     * @param mapper 模板数据访问接口
     * @param auditService 操作审计服务
     * @param objectMapper JSON 映射器
     */
    public CommandTemplateService(
            CommandTemplateMapper mapper,
            OperationAuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.mapper = mapper;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /** @return 全部自定义模板版本 */
    @Transactional(readOnly = true)
    public List<CommandTemplateResponse> list() {
        return mapper.findAll().stream().map(this::toResponse).toList();
    }

    /** @return 当前允许绑定命令模板的活动产品 */
    @Transactional(readOnly = true)
    public List<CommandTemplateProductResponse> listBindableProducts() {
        return mapper.findBindableProducts();
    }

    /**
     * 查询设备产品绑定的已发布自定义模板。
     *
     * @param deviceId 设备标识
     * @return 已发布模板
     */
    @Transactional(readOnly = true)
    public List<CommandTemplateResponse> findPublishedForDevice(UUID deviceId) {
        return mapper.findPublishedByDeviceId(deviceId).stream().map(this::toResponse).toList();
    }

    /**
     * 读取设备可执行的已发布模板。
     *
     * @param deviceId 设备标识
     * @param templateKey 模板编码
     * @return 已发布模板
     */
    @Transactional(readOnly = true)
    public CommandTemplateResponse requirePublishedForDevice(UUID deviceId, String templateKey) {
        return toResponse(mapper.findPublishedByDeviceAndKey(deviceId, normalizeKey(templateKey))
                .orElseThrow(() -> new MineException(
                        TEMPLATE_NOT_FOUND, Map.of("templateKey", String.valueOf(templateKey)))));
    }

    /**
     * 创建模板及首个草稿版本。
     *
     * @param request 草稿请求
     * @return 新草稿
     */
    @Transactional
    public CommandTemplateResponse create(CommandTemplateRequest request) {
        String templateKey = normalizeKey(request.templateKey());
        if (mapper.findByTemplateKey(templateKey).isPresent()) {
            throw duplicate(templateKey);
        }
        List<UUID> products = validateProducts(request.productIds());
        UUID operatorId = currentOperatorId();
        UUID templateId = idGenerator.next();
        UUID versionId = idGenerator.next();
        TemplateDraft draft = draft(templateId, versionId, 1, request, operatorId);
        try {
            requireOne(mapper.insertTemplate(templateId, templateKey, operatorId), "命令模板");
            requireOne(mapper.insertVersion(draft), "命令模板版本");
            replaceBindings(versionId, products, operatorId, false);
        } catch (DuplicateKeyException exception) {
            throw duplicate(templateKey);
        }
        TemplateRecord saved = mapper.findByTemplateKey(templateKey).orElseThrow(
                () -> new IllegalStateException("命令模板写入后无法读取"));
        auditService.recordSuccess(
                "COMMAND_TEMPLATE", "CREATE_COMMAND_TEMPLATE", "command:template:edit",
                "COMMAND_TEMPLATE_VERSION", saved.versionId(), Map.of(), summary(saved, products)
        );
        return toResponse(saved);
    }

    /**
     * 使用乐观锁替换模板草稿内容和产品绑定。
     *
     * @param versionId 模板版本标识
     * @param request 草稿请求
     * @return 修改后草稿
     */
    @Transactional
    public CommandTemplateResponse update(UUID versionId, CommandTemplateRequest request) {
        TemplateRecord existing = requireVersion(versionId);
        requireDraft(existing);
        if (!existing.templateKey().equals(normalizeKey(request.templateKey()))) {
            throw invalid("templateKey");
        }
        if (existing.version() != request.version()) {
            throw conflict(versionId);
        }
        List<UUID> previousProducts = mapper.findProductIds(versionId);
        List<UUID> products = validateProducts(request.productIds());
        UUID operatorId = currentOperatorId();
        TemplateDraft draft = draft(
                existing.templateId(), existing.versionId(), existing.versionNumber(), request, operatorId);
        if (mapper.updateDraft(draft, request.version()) != 1) {
            throw conflict(versionId);
        }
        replaceBindings(versionId, products, operatorId, true);
        TemplateRecord saved = requireVersion(versionId);
        auditService.recordSuccess(
                "COMMAND_TEMPLATE", "UPDATE_COMMAND_TEMPLATE", "command:template:edit",
                "COMMAND_TEMPLATE_VERSION", versionId,
                summary(existing, previousProducts), summary(saved, products)
        );
        return toResponse(saved);
    }

    /**
     * 将已发布的不可变版本复制为下一个可编辑草稿，并保留产品绑定。
     *
     * @param sourceVersionId 来源已发布版本标识
     * @return 新创建的下一版草稿
     */
    @Transactional
    public CommandTemplateResponse createNextDraft(UUID sourceVersionId) {
        TemplateRecord source = requireVersion(sourceVersionId);
        if (!"PUBLISHED".equals(source.versionStatus())) {
            throw new MineException(REVISION_SOURCE_INVALID, Map.of("versionId", sourceVersionId));
        }
        if (mapper.draftExists(source.templateId())) {
            throw conflict(sourceVersionId);
        }
        List<UUID> products = mapper.findProductIds(sourceVersionId);
        if (products.isEmpty()) {
            throw new MineException(PRODUCT_INVALID, Map.of("versionId", sourceVersionId));
        }
        UUID operatorId = currentOperatorId();
        UUID versionId = idGenerator.next();
        TemplateDraft draft = new TemplateDraft(
                source.templateId(), versionId, mapper.nextVersionNumber(source.templateId()),
                source.nameZh(), source.nameEn(), source.description(), source.payloadFormat(),
                source.parameterSchemaJson(), source.rawPayload(), source.rawPayloadHex(),
                source.rawPayloadSha256(), source.defaultTtlSeconds(),
                source.defaultTimeoutSeconds(), source.requiresReason(), source.replyExpected(),
                operatorId
        );
        try {
            requireOne(mapper.insertVersion(draft), "命令模板版本");
            replaceBindings(versionId, products, operatorId, false);
        } catch (DuplicateKeyException exception) {
            throw conflict(sourceVersionId);
        }
        TemplateRecord saved = mapper.findByTemplateKey(source.templateKey())
                .filter(record -> record.versionId().equals(versionId))
                .orElseThrow(() -> new IllegalStateException("下一版命令模板草稿写入后无法读取"));
        auditService.recordSuccess(
                "COMMAND_TEMPLATE", "CREATE_COMMAND_TEMPLATE_REVISION", "command:template:edit",
                "COMMAND_TEMPLATE_VERSION", versionId,
                summary(source, products), summary(saved, products)
        );
        return toResponse(saved);
    }

    /**
     * 发布草稿并退役同一模板的旧发布版本。
     *
     * @param versionId 模板版本标识
     * @param expectedVersion 期望乐观锁版本
     * @return 已发布版本
     */
    @Transactional
    public CommandTemplateResponse publish(UUID versionId, long expectedVersion) {
        TemplateRecord existing = requireVersion(versionId);
        requireDraft(existing);
        if (existing.version() != expectedVersion) {
            throw conflict(versionId);
        }
        List<UUID> products = mapper.findProductIds(versionId);
        if (products.isEmpty()) {
            throw new MineException(PRODUCT_INVALID, Map.of("versionId", versionId));
        }
        UUID operatorId = currentOperatorId();
        Instant occurredAt = Instant.now();
        mapper.retirePublished(existing.templateId(), versionId, operatorId, occurredAt);
        if (mapper.publishVersion(versionId, expectedVersion, operatorId, occurredAt) != 1) {
            throw conflict(versionId);
        }
        TemplateRecord saved = requireVersion(versionId);
        auditService.recordSuccess(
                "COMMAND_TEMPLATE", "PUBLISH_COMMAND_TEMPLATE", "command:template:publish",
                "COMMAND_TEMPLATE_VERSION", versionId,
                Map.of("status", "DRAFT"), summary(saved, products)
        );
        return toResponse(saved);
    }

    private TemplateDraft draft(
            UUID templateId,
            UUID versionId,
            int versionNumber,
            CommandTemplateRequest request,
            UUID operatorId
    ) {
        validateTime(request.defaultTtlSeconds(), request.defaultTimeoutSeconds());
        String nameZh = requireText(request.nameZh(), 128, "nameZh");
        String nameEn = normalizeOptional(request.nameEn(), 128, "nameEn");
        String description = normalizeOptional(request.description(), 1000, "description");
        Map<String, Object> schema = validateSchema(request.parameterSchema());
        RawHexPayload raw = null;
        boolean replyExpected;
        if ("RAW_HEX".equals(request.payloadFormat())) {
            if (!schemaProperties(schema).isEmpty() || schema.containsKey("required")) {
                throw invalid("parameterSchema");
            }
            raw = parseRaw(request.rawPayloadHex());
            replyExpected = false;
        } else if ("MQTT_JSON".equals(request.payloadFormat())) {
            if (request.rawPayloadHex() != null && !request.rawPayloadHex().isBlank()) {
                throw invalid("rawPayloadHex");
            }
            replyExpected = true;
        } else {
            throw invalid("payloadFormat");
        }
        return new TemplateDraft(
                templateId, versionId, versionNumber, nameZh, nameEn, description,
                request.payloadFormat(), writeJson(schema), raw == null ? null : raw.bytes(),
                raw == null ? null : raw.normalizedHex(), raw == null ? null : raw.sha256(),
                request.defaultTtlSeconds(), request.defaultTimeoutSeconds(),
                request.requiresReason(), replyExpected, operatorId
        );
    }

    private List<UUID> validateProducts(List<UUID> source) {
        List<UUID> products = new ArrayList<>(new LinkedHashSet<>(
                Optional.ofNullable(source).orElse(List.of())));
        if (products.isEmpty()) {
            throw new MineException(PRODUCT_INVALID, Map.of());
        }
        for (UUID productId : products) {
            if (productId == null || !mapper.productExists(productId)) {
                throw new MineException(PRODUCT_INVALID, Map.of(
                        "productId", String.valueOf(productId)));
            }
        }
        return List.copyOf(products);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> validateSchema(Map<String, Object> source) {
        if (source == null || !"object".equals(source.get("type"))
                || !Boolean.FALSE.equals(source.get("additionalProperties"))
                || !SCHEMA_ROOT_KEYS.containsAll(source.keySet())
                || !(source.get("properties") instanceof Map<?, ?> properties)) {
            throw invalid("parameterSchema");
        }
        Set<String> propertyNames = new HashSet<>();
        for (Map.Entry<?, ?> entry : properties.entrySet()) {
            if (!(entry.getKey() instanceof String name) || name.isBlank()
                    || !propertyNames.add(name)
                    || !(entry.getValue() instanceof Map<?, ?> definition)
                    || !PROPERTY_KEYS.containsAll((Set<String>) definition.keySet())
                    || !PROPERTY_TYPES.contains(definition.get("type"))) {
                throw invalid("parameterSchema");
            }
            Object values = definition.get("enum");
            if (values != null && (!(values instanceof List<?> list) || list.isEmpty()
                    || list.stream().anyMatch(value -> value instanceof Map<?, ?> || value instanceof List<?>))) {
                throw invalid("parameterSchema");
            }
        }
        Object required = source.get("required");
        if (required != null && (!(required instanceof List<?> list)
                || list.stream().anyMatch(value -> !(value instanceof String name)
                        || !propertyNames.contains(name)))) {
            throw invalid("parameterSchema");
        }
        return Map.copyOf(source);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> schemaProperties(Map<String, Object> schema) {
        return (Map<String, Object>) schema.get("properties");
    }

    private void replaceBindings(
            UUID versionId,
            List<UUID> products,
            UUID operatorId,
            boolean deleteExisting
    ) {
        if (deleteExisting) {
            mapper.deleteBindings(versionId);
        }
        for (UUID productId : products) {
            requireOne(mapper.insertBinding(idGenerator.next(), versionId, productId, operatorId),
                    "命令模板产品绑定");
        }
    }

    private CommandTemplateResponse toResponse(TemplateRecord record) {
        return new CommandTemplateResponse(
                record.templateId(), record.versionId(), record.templateKey(), record.versionNumber(),
                record.versionStatus(), record.nameZh(), record.nameEn(), record.description(),
                record.riskLevel(), record.payloadFormat(), readSchema(record.parameterSchemaJson()),
                record.rawPayloadHex(), record.rawPayloadSha256(), record.defaultTtlSeconds(),
                record.defaultTimeoutSeconds(), record.requiresReason(), record.replyExpected(),
                mapper.findProductIds(record.versionId()), record.createdAt(), record.updatedAt(),
                record.publishedAt(), record.version()
        );
    }

    private Map<String, Object> readSchema(String json) {
        try {
            return Map.copyOf(objectMapper.readValue(json, new TypeReference<>() { }));
        } catch (JacksonException exception) {
            throw new IllegalStateException("命令模板参数 Schema 无法解析", exception);
        }
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("命令模板参数 Schema 无法序列化", exception);
        }
    }

    private TemplateRecord requireVersion(UUID versionId) {
        return mapper.findByVersionId(versionId).orElseThrow(
                () -> new MineException(TEMPLATE_NOT_FOUND, Map.of("versionId", versionId)));
    }

    private void requireDraft(TemplateRecord record) {
        if (!"DRAFT".equals(record.versionStatus())) {
            throw new MineException(DRAFT_REQUIRED, Map.of("versionId", record.versionId()));
        }
    }

    private RawHexPayload parseRaw(String value) {
        try {
            return RawHexPayload.parse(value);
        } catch (IllegalArgumentException exception) {
            throw new MineException(TEMPLATE_INVALID, Map.of(
                    "field", "rawPayloadHex", "reason", exception.getMessage()));
        }
    }

    private void validateTime(int ttl, int timeout) {
        if (ttl < 1 || ttl > 86400 || timeout < 1 || timeout > ttl) {
            throw invalid("timeout");
        }
    }

    private String normalizeKey(String value) {
        String normalized = value == null ? "" : value.strip().toUpperCase();
        if (!TEMPLATE_KEY.matcher(normalized).matches()) {
            throw invalid("templateKey");
        }
        return normalized;
    }

    private String requireText(String value, int maxLength, String field) {
        String normalized = normalizeOptional(value, maxLength, field);
        if (normalized == null) throw invalid(field);
        return normalized;
    }

    private String normalizeOptional(String value, int maxLength, String field) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        if (normalized.length() > maxLength) throw invalid(field);
        return normalized;
    }

    private UUID currentOperatorId() {
        try {
            return UUID.fromString(StpUtil.getLoginIdAsString());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private void requireOne(int affectedRows, String resource) {
        if (affectedRows != 1) {
            throw new IllegalStateException(resource + "写入行数异常");
        }
    }

    private Map<String, Object> summary(TemplateRecord record, List<UUID> productIds) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("templateKey", record.templateKey());
        summary.put("versionNumber", record.versionNumber());
        summary.put("status", record.versionStatus());
        summary.put("payloadFormat", record.payloadFormat());
        summary.put("defaultTtlSeconds", record.defaultTtlSeconds());
        summary.put("defaultTimeoutSeconds", record.defaultTimeoutSeconds());
        summary.put("requiresReason", record.requiresReason());
        summary.put("replyExpected", record.replyExpected());
        summary.put("productIds", productIds);
        summary.put("version", record.version());
        if (record.rawPayloadSha256() != null) {
            summary.put("payloadSha256", record.rawPayloadSha256());
            summary.put("byteLength", record.rawPayloadHex().length() / 2);
        }
        return Map.copyOf(summary);
    }

    private MineException duplicate(String templateKey) {
        return new MineException(TEMPLATE_DUPLICATE, Map.of("templateKey", templateKey));
    }

    private MineException conflict(UUID versionId) {
        return new MineException(TEMPLATE_CONFLICT, Map.of("versionId", versionId));
    }

    private MineException invalid(String field) {
        return new MineException(TEMPLATE_INVALID, Map.of("field", field));
    }
}
