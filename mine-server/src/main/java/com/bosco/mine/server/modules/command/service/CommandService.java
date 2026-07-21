package com.bosco.mine.server.modules.command.service;

import cn.dev33.satoken.stp.StpUtil;
import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.common.time.MineClock;
import com.bosco.mine.messaging.command.CommandDispatchPayload;
import com.bosco.mine.server.modules.command.dto.CommandResponse;
import com.bosco.mine.server.modules.command.model.CommandInstance;
import com.bosco.mine.server.modules.command.model.CommandRiskLevel;
import com.bosco.mine.server.modules.command.model.CommandStatus;
import com.bosco.mine.server.modules.command.outbox.OutboxCommitNotifier;
import com.bosco.mine.server.modules.command.outbox.OutboxMapper;
import com.bosco.mine.server.modules.command.repository.CommandMapper;
import com.bosco.mine.server.modules.command.repository.StoredCommand;
import com.bosco.mine.server.modules.command.template.CommandTemplateResponse;
import com.bosco.mine.server.modules.command.template.CommandTemplateService;
import com.bosco.mine.server.modules.command.template.RawHexPayload;
import com.bosco.mine.server.modules.device.repository.DeviceConnectionProfileMapper;
import com.bosco.mine.server.modules.device.service.DeviceAccessStatusService;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 创建设备命令，并在同一 PostgreSQL 事务内写入可靠出站事件。
 */
@Service
public class CommandService {
    private static final ErrorCode DEVICE_UNAVAILABLE =
            new ErrorCode("COMMAND-422-002", "目标设备不存在或未启用");
    private static final ErrorCode TIME_CONSTRAINT_INVALID =
            new ErrorCode("COMMAND-422-004", "命令 TTL 或回复超时不合法");
    private static final ErrorCode IDEMPOTENCY_CONFLICT =
            new ErrorCode("COMMAND-409-001", "幂等键已用于其他命令请求");
    private static final ErrorCode CANCEL_CONFLICT =
            new ErrorCode("COMMAND-409-002", "命令已开始发送或状态已变化，不能取消");
    private static final ErrorCode COMMAND_NOT_FOUND =
            new ErrorCode("COMMAND-404-001", "命令不存在");
    private static final ErrorCode REASON_REQUIRED =
            new ErrorCode("COMMAND-422-005", "该命令必须填写现场下发原因");
    private static final ErrorCode RAW_TARGET_UNAVAILABLE =
            new ErrorCode("COMMAND-422-006", "临时十六进制命令仅支持在线 MQTT 设备");
    private static final ErrorCode RAW_PAYLOAD_INVALID =
            new ErrorCode("COMMAND-422-007", "十六进制载荷不合法");
    private static final ErrorCode RAW_MQTT_REQUIRED =
            new ErrorCode("COMMAND-422-008", "十六进制命令仅支持 MQTT 接入设备");
    private static final ErrorCode PARAMETERS_INVALID =
            new ErrorCode("COMMAND-422-003", "命令参数不符合模板约束");

    private final CommandMapper commandMapper;
    private final OutboxMapper outboxMapper;
    private final OutboxCommitNotifier commitNotifier;
    private final OperationAuditService auditService;
    private final CommandTemplateService templateService;
    private final DeviceConnectionProfileMapper connectionProfileMapper;
    private final DeviceAccessStatusService accessStatusService;
    private final IdGenerator idGenerator = IdGenerator.timeOrdered();
    private final MineClock clock = MineClock.systemUtc();
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    /**
     * 创建命令服务。
     *
     * @param commandMapper 命令数据访问接口
     * @param outboxMapper Outbox 数据访问接口
     * @param commitNotifier 事务提交通知器
     * @param auditService 操作审计服务
     * @param templateService 自定义命令模板服务
     * @param connectionProfileMapper 设备接入配置访问接口
     * @param accessStatusService 设备接入状态服务
     */
    public CommandService(
            CommandMapper commandMapper,
            OutboxMapper outboxMapper,
            OutboxCommitNotifier commitNotifier,
            OperationAuditService auditService,
            CommandTemplateService templateService,
            DeviceConnectionProfileMapper connectionProfileMapper,
            DeviceAccessStatusService accessStatusService
    ) {
        this.commandMapper = commandMapper;
        this.outboxMapper = outboxMapper;
        this.commitNotifier = commitNotifier;
        this.auditService = auditService;
        this.templateService = templateService;
        this.connectionProfileMapper = connectionProfileMapper;
        this.accessStatusService = accessStatusService;
    }

    /**
     * 以当前登录用户身份创建低风险命令。
     *
     * @param deviceId 目标设备标识
     * @param templateKey 模板编码
     * @param parameters 命令参数
     * @param idempotencyKey 幂等键
     * @param ttlSeconds TTL 秒数
     * @param timeoutSeconds 回复超时秒数
     * @param traceId 链路标识
     * @return 新创建或原幂等命令响应
     */
    @Transactional
    public CommandResponse createCurrentUserCommand(
            UUID deviceId,
            String templateKey,
            Map<String, Object> parameters,
            String idempotencyKey,
            int ttlSeconds,
            int timeoutSeconds,
            String traceId
    ) {
        return createCurrentUserCommand(
                deviceId, templateKey, parameters, null, null, idempotencyKey,
                ttlSeconds, timeoutSeconds, traceId
        );
    }

    /**
     * 以当前登录用户身份创建带现场原因和作业单号的命令。
     */
    @Transactional
    public CommandResponse createCurrentUserCommand(
            UUID deviceId,
            String templateKey,
            Map<String, Object> parameters,
            String reason,
            String workOrderNo,
            String idempotencyKey,
            int ttlSeconds,
            int timeoutSeconds,
            String traceId
    ) {
        UUID userId = UUID.fromString(StpUtil.getLoginIdAsString());
        CommandInstance command = createLowRiskCommand(
                userId, deviceId, templateKey, parameters, reason, workOrderNo, idempotencyKey,
                ttlSeconds, timeoutSeconds, traceId
        );
        CommandResponse response = toResponse(commandMapper.findById(command.id()).orElseThrow());
        auditService.recordSuccess(
                "COMMAND_CENTER",
                "SUBMIT_COMMAND",
                "command:instance:create",
                "COMMAND",
                response.id(),
                Map.of(),
                Map.of(
                        "deviceId", response.deviceId(),
                        "templateKey", response.templateKey(),
                        "riskLevel", response.riskLevel(),
                        "status", response.status(),
                        "ttlSeconds", response.ttlSeconds(),
                        "timeoutSeconds", response.timeoutSeconds()
                )
        );
        return response;
    }

    /**
     * 以当前登录用户身份向在线 MQTT 设备下发一次临时原始十六进制命令。
     *
     * @param deviceId 目标设备标识
     * @param rawPayloadHex 十六进制载荷
     * @param reason 现场下发原因
     * @param idempotencyKey 幂等键
     * @param ttlSeconds TTL 秒数
     * @param traceId 链路标识
     * @return 新创建或原幂等命令响应
     */
    @Transactional
    public CommandResponse createCurrentUserRawHexCommand(
            UUID deviceId,
            String rawPayloadHex,
            String reason,
            String idempotencyKey,
            int ttlSeconds,
            String traceId
    ) {
        UUID userId = UUID.fromString(StpUtil.getLoginIdAsString());
        String normalizedReason = normalizeOptional(reason, 1000, "下发原因");
        if (normalizedReason == null) {
            throw new MineException(REASON_REQUIRED, Map.of("templateKey", "RAW_HEX_AD_HOC"));
        }
        RawHexPayload raw = parseRawPayload(rawPayloadHex);
        ExecutionDefinition definition = new ExecutionDefinition(
                "RAW_HEX_AD_HOC", 1, true, "AD_HOC_HEX", "RAW_HEX",
                raw, false, Map.of()
        );
        CommandInstance command = createCommand(
                userId, deviceId, definition, Map.of(), normalizedReason, null,
                idempotencyKey, ttlSeconds, 1, traceId
        );
        CommandResponse response = toResponse(commandMapper.findById(command.id()).orElseThrow());
        auditService.recordSuccess(
                "COMMAND_CENTER", "SUBMIT_RAW_HEX_COMMAND", "command:raw:execute",
                "COMMAND", response.id(), Map.of(), Map.of(
                        "deviceId", response.deviceId(),
                        "templateKey", response.templateKey(),
                        "payloadFormat", response.payloadFormat(),
                        "payloadSha256", raw.sha256(),
                        "byteLength", raw.byteLength(),
                        "reason", normalizedReason,
                        "status", response.status()
                )
        );
        return response;
    }

    /**
     * 创建并入队已发布的低风险设备状态查询命令。
     *
     * @param createdBy 命令创建人标识
     * @param deviceId 目标设备标识
     * @param templateKey 命令模板编码
     * @param parameters 命令参数
     * @param idempotencyKey 幂等键
     * @param ttlSeconds TTL 秒数
     * @param timeoutSeconds 设备回复超时秒数
     * @param traceId 链路追踪标识
     * @return 新创建或原幂等命令
     */
    @Transactional
    public CommandInstance createLowRiskCommand(
            UUID createdBy,
            UUID deviceId,
            String templateKey,
            Map<String, Object> parameters,
            String idempotencyKey,
            int ttlSeconds,
            int timeoutSeconds,
            String traceId
    ) {
        return createLowRiskCommand(
                createdBy, deviceId, templateKey, parameters, null, null,
                idempotencyKey, ttlSeconds, timeoutSeconds, traceId
        );
    }

    /**
     * 创建并入队带现场原因和作业单号的低风险命令。
     */
    @Transactional
    public CommandInstance createLowRiskCommand(
            UUID createdBy,
            UUID deviceId,
            String templateKey,
            Map<String, Object> parameters,
            String reason,
            String workOrderNo,
            String idempotencyKey,
            int ttlSeconds,
            int timeoutSeconds,
            String traceId
    ) {
        Objects.requireNonNull(createdBy, "命令创建人不能为空");
        Objects.requireNonNull(deviceId, "设备标识不能为空");
        String normalizedTemplate = requireText(templateKey, 128, "命令模板编码");
        ExecutionDefinition definition = resolveDefinition(
                deviceId, normalizedTemplate, Objects.requireNonNull(parameters, "命令参数不能为空"));
        return createCommand(
                createdBy, deviceId, definition, parameters, reason, workOrderNo,
                idempotencyKey, ttlSeconds, timeoutSeconds, traceId
        );
    }

    private CommandInstance createCommand(
            UUID createdBy,
            UUID deviceId,
            ExecutionDefinition definition,
            Map<String, Object> parameters,
            String reason,
            String workOrderNo,
            String idempotencyKey,
            int ttlSeconds,
            int timeoutSeconds,
            String traceId
    ) {
        String normalizedReason = normalizeOptional(reason, 1000, "下发原因");
        String normalizedWorkOrder = normalizeOptional(workOrderNo, 128, "作业单号");
        if (definition.requiresReason() && normalizedReason == null) {
            throw new MineException(REASON_REQUIRED, Map.of("templateKey", definition.templateKey()));
        }
        validateTimeConstraints(ttlSeconds, timeoutSeconds);
        String normalizedKey = requireText(idempotencyKey, 128, "幂等键");
        String normalizedTraceId = requireText(traceId, 64, "链路追踪标识");
        var storedExisting = commandMapper.findByIdempotencyKey(createdBy, normalizedKey);
        if (storedExisting.isPresent()) {
            verifySameIdempotentRequest(
                    storedExisting.orElseThrow(), deviceId, definition, parameters,
                    normalizedReason, normalizedWorkOrder, ttlSeconds, timeoutSeconds
            );
            return toCommandInstance(storedExisting.orElseThrow());
        }
        if ("RAW_HEX".equals(definition.payloadFormat())) {
            if ("AD_HOC_HEX".equals(definition.dispatchSource())) {
                requireOnlineMqttDevice(deviceId);
            } else {
                requireMqttDevice(deviceId);
            }
        }
        if (!"ENABLED".equals(commandMapper.lockDeviceStatus(deviceId).orElse(null))) {
            throw new MineException(DEVICE_UNAVAILABLE, Map.of("deviceId", deviceId));
        }

        Instant now = clock.instant();
        CommandInstance queued = CommandInstance.createLowRisk(
                idGenerator.next(), deviceId, definition.templateKey(), definition.templateVersion(), parameters,
                normalizedReason, normalizedWorkOrder,
                normalizedKey, createdBy, now, ttlSeconds, timeoutSeconds
        ).transitionTo(CommandStatus.QUEUED, now);
        RawHexPayload raw = definition.rawPayload();
        int inserted = commandMapper.insert(
                queued, writeJson(queued.parameters()), definition.dispatchSource(),
                definition.payloadFormat(), raw == null ? null : raw.bytes(),
                raw == null ? null : raw.normalizedHex(), raw == null ? null : raw.sha256(),
                definition.replyExpected()
        );
        if (inserted == 0) {
            StoredCommand stored = loadByIdempotencyKey(createdBy, normalizedKey);
            verifySameIdempotentRequest(
                    stored, deviceId, definition, parameters, normalizedReason,
                    normalizedWorkOrder, ttlSeconds, timeoutSeconds
            );
            return toCommandInstance(stored);
        }

        UUID eventId = idGenerator.next();
        outboxMapper.insertCommandDispatch(
                eventId,
                queued.id(),
                writeJson(new CommandDispatchPayload(
                        queued.id(), queued.deviceId(), queued.templateKey(),
                        queued.templateVersion(), queued.parameters(),
                        queued.riskLevel().name(), queued.ttlSeconds(),
                        queued.timeoutSeconds(), queued.expiresAt(),
                        definition.payloadFormat(), raw == null ? null
                                : Base64.getEncoder().encodeToString(raw.bytes()),
                        definition.replyExpected()
                )),
                writeJson(Map.of(
                        "traceId", normalizedTraceId,
                        "partitionKey", queued.deviceId().toString()
                ))
        );
        commitNotifier.notifyAfterCommit(eventId);
        return queued;
    }

    private StoredCommand loadByIdempotencyKey(UUID createdBy, String idempotencyKey) {
        return commandMapper.findByIdempotencyKey(createdBy, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("幂等命令写入后无法读取"));
    }

    /**
     * 将数据库命令行转换为不可变领域快照。
     *
     * @param stored 数据库命令行
     * @return 命令领域快照
     */
    private CommandInstance toCommandInstance(StoredCommand stored) {
        return new CommandInstance(
                stored.id(), stored.deviceId(), stored.templateKey(), stored.templateVersion(),
                readMap(stored.parametersJson()), stored.reason(), stored.workOrderNo(),
                stored.cancelledBy(), stored.cancelReason(), CommandRiskLevel.valueOf(stored.riskLevel()),
                CommandStatus.valueOf(stored.status()), stored.ttlSeconds(), stored.timeoutSeconds(),
                stored.idempotencyKey(), stored.protocolVersion(), stored.configReleaseId(), stored.createdBy(),
                stored.createdAt(), stored.updatedAt(), stored.queuedAt(), stored.sentAt(),
                stored.deviceReceivedAt(), stored.completedAt(), stored.expiresAt(),
                stored.lastErrorCode(), stored.replyJson() == null ? null : readMap(stored.replyJson()),
                stored.version()
        );
    }

    /**
     * 核对同一用户和幂等键对应的请求语义是否完全一致。
     *
     * @param stored 已存在命令
     * @param deviceId 本次目标设备标识
     * @param templateKey 本次模板编码
     * @param parameters 本次命令参数
     * @param ttlSeconds 本次 TTL 秒数
     * @param timeoutSeconds 本次回复超时秒数
     */
    private void verifySameIdempotentRequest(
            StoredCommand stored,
            UUID deviceId,
            ExecutionDefinition definition,
            Map<String, Object> parameters,
            String reason,
            String workOrderNo,
            int ttlSeconds,
            int timeoutSeconds
    ) {
        CommandInstance existing = toCommandInstance(stored);
        boolean sameRequest = existing.deviceId().equals(deviceId)
                && existing.templateKey().equals(definition.templateKey())
                && existing.templateVersion() == definition.templateVersion()
                && existing.parameters().equals(parameters)
                && Objects.equals(existing.reason(), reason)
                && Objects.equals(existing.workOrderNo(), workOrderNo)
                && existing.ttlSeconds() == ttlSeconds
                && existing.timeoutSeconds() == timeoutSeconds
                && Objects.equals(stored.dispatchSource(), definition.dispatchSource())
                && Objects.equals(stored.payloadFormat(), definition.payloadFormat())
                && Objects.equals(stored.payloadSha256(), definition.rawPayload() == null
                        ? null : definition.rawPayload().sha256())
                && stored.replyExpected() == definition.replyExpected();
        if (!sameRequest) {
            throw new MineException(IDEMPOTENCY_CONFLICT, Map.of("commandId", existing.id()));
        }
    }

    private ExecutionDefinition resolveDefinition(
            UUID deviceId,
            String templateKey,
            Map<String, Object> parameters
    ) {
        Optional<com.bosco.mine.server.modules.command.dto.CommandTemplateResponse> builtIn =
                PublishedCommandTemplates.publishedTemplates().stream()
                        .filter(template -> template.key().equals(templateKey))
                        .findFirst();
        if (builtIn.isPresent()) {
            PublishedCommandTemplates.validateParameters(templateKey, parameters);
            var template = builtIn.orElseThrow();
            return new ExecutionDefinition(
                    template.key(), template.version(), template.requiresReason(),
                    "BUILTIN_TEMPLATE", template.payloadFormat(), null,
                    template.replyExpected(), template.parameterSchema()
            );
        }
        CommandTemplateResponse template;
        try {
            template = templateService.requirePublishedForDevice(deviceId, templateKey);
        } catch (MineException exception) {
            if ("COMMAND-TEMPLATE-404-001".equals(exception.errorCode().code())) {
                PublishedCommandTemplates.validateParameters(templateKey, parameters);
            }
            throw exception;
        }
        validateCustomParameters(template.templateKey(), template.parameterSchema(), parameters);
        RawHexPayload raw = null;
        if ("RAW_HEX".equals(template.payloadFormat())) {
            raw = parseRawPayload(template.rawPayloadHex());
        }
        return new ExecutionDefinition(
                template.templateKey(), template.versionNumber(), template.requiresReason(),
                "CUSTOM_TEMPLATE", template.payloadFormat(), raw,
                template.replyExpected(), template.parameterSchema()
        );
    }

    @SuppressWarnings("unchecked")
    private void validateCustomParameters(
            String templateKey,
            Map<String, Object> schema,
            Map<String, Object> parameters
    ) {
        if (parameters == null || !(schema.get("properties") instanceof Map<?, ?> sourceProperties)) {
            throw invalidParameters(templateKey);
        }
        Map<String, Map<String, Object>> properties = (Map<String, Map<String, Object>>) sourceProperties;
        if (!properties.keySet().containsAll(parameters.keySet())) {
            throw invalidParameters(templateKey);
        }
        Object requiredValue = schema.get("required");
        if (requiredValue instanceof List<?> required
                && required.stream().anyMatch(name -> !parameters.containsKey(name))) {
            throw invalidParameters(templateKey);
        }
        for (Map.Entry<String, Object> parameter : parameters.entrySet()) {
            Map<String, Object> definition = properties.get(parameter.getKey());
            if (!matchesDefinition(parameter.getValue(), definition)) {
                throw invalidParameters(templateKey);
            }
        }
    }

    private boolean matchesDefinition(Object value, Map<String, Object> definition) {
        if (definition == null || value == null) return false;
        String type = String.valueOf(definition.get("type"));
        boolean typeMatches = switch (type) {
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Number number
                    && number.doubleValue() == Math.rint(number.doubleValue());
            default -> false;
        };
        if (!typeMatches) return false;
        if (definition.get("enum") instanceof List<?> values && !values.contains(value)) return false;
        if (value instanceof Number number) {
            if (definition.get("minimum") instanceof Number minimum
                    && number.doubleValue() < minimum.doubleValue()) return false;
            if (definition.get("maximum") instanceof Number maximum
                    && number.doubleValue() > maximum.doubleValue()) return false;
        }
        return true;
    }

    private MineException invalidParameters(String templateKey) {
        return new MineException(PARAMETERS_INVALID, Map.of("templateKey", templateKey));
    }

    private RawHexPayload parseRawPayload(String value) {
        try {
            return RawHexPayload.parse(value);
        } catch (IllegalArgumentException exception) {
            throw new MineException(RAW_PAYLOAD_INVALID, Map.of("reason", exception.getMessage()));
        }
    }

    private void requireOnlineMqttDevice(UUID deviceId) {
        boolean mqtt = isMqttDevice(deviceId);
        boolean online = mqtt && accessStatusService.find(List.of(deviceId)).stream()
                .anyMatch(status -> "ONLINE".equals(status.status()));
        if (!online) {
            throw new MineException(RAW_TARGET_UNAVAILABLE, Map.of("deviceId", deviceId));
        }
    }

    private void requireMqttDevice(UUID deviceId) {
        if (!isMqttDevice(deviceId)) {
            throw new MineException(RAW_MQTT_REQUIRED, Map.of("deviceId", deviceId));
        }
    }

    private boolean isMqttDevice(UUID deviceId) {
        return connectionProfileMapper.findByDeviceId(deviceId)
                .map(profile -> "DEVICE_TO_PLATFORM_MQTT".equals(profile.connectionMode()))
                .orElse(false);
    }

    /**
     * 取消尚未被出站中继领取的排队命令。
     *
     * @param commandId 命令标识
     * @param version 期望状态版本
     * @param cancelReason 取消原因
     * @return 取消后的命令
     */
    @Transactional
    public CommandResponse cancelCurrentUserCommand(UUID commandId, long version, String cancelReason) {
        StoredCommand command = commandMapper.lockById(commandId)
                .orElseThrow(() -> new MineException(COMMAND_NOT_FOUND, Map.of("commandId", commandId)));
        String normalizedReason = requireText(cancelReason, 500, "取消原因");
        if (!"QUEUED".equals(command.status()) || command.version() != version
                || commandMapper.cancelPendingDispatch(commandId) == 0) {
            throw new MineException(CANCEL_CONFLICT, Map.of("commandId", commandId));
        }
        UUID operatorId = UUID.fromString(StpUtil.getLoginIdAsString());
        if (commandMapper.cancelQueuedCommand(commandId, version, operatorId, normalizedReason) == 0) {
            throw new MineException(CANCEL_CONFLICT, Map.of("commandId", commandId));
        }
        CommandResponse response = toResponse(commandMapper.findById(commandId).orElseThrow());
        auditService.recordSuccess(
                "COMMAND_CENTER", "CANCEL_COMMAND", "command:instance:cancel", "COMMAND", commandId,
                Map.of("status", command.status()), Map.of("status", response.status(), "reason", normalizedReason)
        );
        return response;
    }

    private void validateTimeConstraints(int ttlSeconds, int timeoutSeconds) {
        if (ttlSeconds < 1 || ttlSeconds > 86_400
                || timeoutSeconds < 1 || timeoutSeconds > ttlSeconds) {
            throw new MineException(TIME_CONSTRAINT_INVALID, Map.of());
        }
    }

    private String writeJson(Object value) {
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("命令消息无法序列化为 JSON", exception);
        }
    }

    private Map<String, Object> readMap(String value) {
        try {
            return jsonMapper.readValue(value, new TypeReference<>() { });
        } catch (JacksonException exception) {
            throw new IllegalStateException("数据库命令 JSON 无法解析", exception);
        }
    }

    private CommandResponse toResponse(StoredCommand stored) {
        CommandInstance command = toCommandInstance(stored);
        return new CommandResponse(
                command.id(), command.deviceId(), command.templateKey(), command.templateVersion(),
                command.parameters(), command.reason(), command.workOrderNo(), command.cancelledBy(),
                command.cancelReason(), stored.dispatchSource(), stored.payloadFormat(),
                stored.rawPayloadHex(), stored.payloadSha256(), stored.replyExpected(),
                command.riskLevel().name(), command.status().name(), command.ttlSeconds(),
                command.timeoutSeconds(), command.idempotencyKey(), command.createdAt(),
                command.updatedAt(), command.queuedAt(), command.sentAt(), command.deviceReceivedAt(),
                command.completedAt(), command.expiresAt(), command.lastErrorCode(), command.reply(),
                command.version()
        );
    }

    private String requireText(String value, int maxLength, String label) {
        Objects.requireNonNull(value, label + "不能为空");
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(label + "不合法");
        }
        return normalized;
    }

    private String normalizeOptional(String value, int maxLength, String label) {
        if (value == null || value.isBlank()) return null;
        return requireText(value, maxLength, label);
    }

    /**
     * 已解析并可固化执行的命令定义。
     */
    private record ExecutionDefinition(
            String templateKey,
            int templateVersion,
            boolean requiresReason,
            String dispatchSource,
            String payloadFormat,
            RawHexPayload rawPayload,
            boolean replyExpected,
            Map<String, Object> parameterSchema
    ) {
        private ExecutionDefinition {
            parameterSchema = Map.copyOf(parameterSchema);
        }
    }
}
