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
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
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

    private final CommandMapper commandMapper;
    private final OutboxMapper outboxMapper;
    private final OutboxCommitNotifier commitNotifier;
    private final OperationAuditService auditService;
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
     */
    public CommandService(
            CommandMapper commandMapper,
            OutboxMapper outboxMapper,
            OutboxCommitNotifier commitNotifier,
            OperationAuditService auditService
    ) {
        this.commandMapper = commandMapper;
        this.outboxMapper = outboxMapper;
        this.commitNotifier = commitNotifier;
        this.auditService = auditService;
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
        UUID userId = UUID.fromString(StpUtil.getLoginIdAsString());
        CommandResponse response = CommandResponse.from(createLowRiskCommand(
                userId, deviceId, templateKey, parameters, idempotencyKey,
                ttlSeconds, timeoutSeconds, traceId
        ));
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
        Objects.requireNonNull(createdBy, "命令创建人不能为空");
        Objects.requireNonNull(deviceId, "设备标识不能为空");
        String normalizedTemplate = requireText(templateKey, 128, "命令模板编码");
        PublishedCommandTemplates.validateParameters(normalizedTemplate, parameters);
        validateTimeConstraints(ttlSeconds, timeoutSeconds);
        String normalizedKey = requireText(idempotencyKey, 128, "幂等键");
        String normalizedTraceId = requireText(traceId, 64, "链路追踪标识");
        var storedExisting = commandMapper.findByIdempotencyKey(createdBy, normalizedKey);
        if (storedExisting.isPresent()) {
            CommandInstance existing = toCommandInstance(storedExisting.orElseThrow());
            verifySameIdempotentRequest(
                    existing, deviceId, normalizedTemplate, parameters, ttlSeconds, timeoutSeconds
            );
            return existing;
        }
        if (!"ENABLED".equals(commandMapper.lockDeviceStatus(deviceId).orElse(null))) {
            throw new MineException(DEVICE_UNAVAILABLE, Map.of("deviceId", deviceId));
        }

        Instant now = clock.instant();
        CommandInstance queued = CommandInstance.createLowRisk(
                idGenerator.next(), deviceId, normalizedTemplate,
                Objects.requireNonNull(parameters, "命令参数不能为空"),
                normalizedKey, createdBy, now, ttlSeconds, timeoutSeconds
        ).transitionTo(CommandStatus.QUEUED, now);
        int inserted = commandMapper.insert(queued, writeJson(queued.parameters()));
        if (inserted == 0) {
            CommandInstance existing = loadByIdempotencyKey(createdBy, normalizedKey);
            verifySameIdempotentRequest(
                    existing, deviceId, normalizedTemplate, parameters, ttlSeconds, timeoutSeconds
            );
            return existing;
        }

        UUID eventId = idGenerator.next();
        outboxMapper.insertCommandDispatch(
                eventId,
                queued.id(),
                writeJson(new CommandDispatchPayload(
                        queued.id(), queued.deviceId(), queued.templateKey(),
                        queued.templateVersion(), queued.parameters(),
                        queued.riskLevel().name(), queued.ttlSeconds(),
                        queued.timeoutSeconds(), queued.expiresAt()
                )),
                writeJson(Map.of(
                        "traceId", normalizedTraceId,
                        "partitionKey", queued.deviceId().toString()
                ))
        );
        commitNotifier.notifyAfterCommit(eventId);
        return queued;
    }

    private CommandInstance loadByIdempotencyKey(UUID createdBy, String idempotencyKey) {
        StoredCommand stored = commandMapper.findByIdempotencyKey(createdBy, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("幂等命令写入后无法读取"));
        return toCommandInstance(stored);
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
                readMap(stored.parametersJson()), stored.reason(), CommandRiskLevel.valueOf(stored.riskLevel()),
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
     * @param existing 已存在命令
     * @param deviceId 本次目标设备标识
     * @param templateKey 本次模板编码
     * @param parameters 本次命令参数
     * @param ttlSeconds 本次 TTL 秒数
     * @param timeoutSeconds 本次回复超时秒数
     */
    private void verifySameIdempotentRequest(
            CommandInstance existing,
            UUID deviceId,
            String templateKey,
            Map<String, Object> parameters,
            int ttlSeconds,
            int timeoutSeconds
    ) {
        boolean sameRequest = existing.deviceId().equals(deviceId)
                && existing.templateKey().equals(templateKey)
                && existing.templateVersion() == 1
                && existing.parameters().equals(parameters)
                && existing.ttlSeconds() == ttlSeconds
                && existing.timeoutSeconds() == timeoutSeconds;
        if (!sameRequest) {
            throw new MineException(IDEMPOTENCY_CONFLICT, Map.of("commandId", existing.id()));
        }
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

    private String requireText(String value, int maxLength, String label) {
        Objects.requireNonNull(value, label + "不能为空");
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(label + "不合法");
        }
        return normalized;
    }
}
