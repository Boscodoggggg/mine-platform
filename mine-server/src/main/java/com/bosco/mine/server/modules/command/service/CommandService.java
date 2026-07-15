package com.bosco.mine.server.modules.command.service;

import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.common.time.MineClock;
import com.bosco.mine.messaging.command.CommandDispatchPayload;
import com.bosco.mine.server.modules.command.model.CommandInstance;
import com.bosco.mine.server.modules.command.model.CommandRiskLevel;
import com.bosco.mine.server.modules.command.model.CommandStatus;
import com.bosco.mine.server.modules.command.outbox.OutboxCommitNotifier;
import com.bosco.mine.server.modules.command.outbox.OutboxMapper;
import com.bosco.mine.server.modules.command.repository.CommandMapper;
import com.bosco.mine.server.modules.command.repository.StoredCommand;
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
    private static final String LOW_RISK_TEMPLATE = "QUERY_STATUS";

    private final CommandMapper commandMapper;
    private final OutboxMapper outboxMapper;
    private final OutboxCommitNotifier commitNotifier;
    private final IdGenerator idGenerator = IdGenerator.timeOrdered();
    private final MineClock clock = MineClock.systemUtc();
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    /**
     * 创建命令服务。
     *
     * @param commandMapper 命令数据访问接口
     * @param outboxMapper Outbox 数据访问接口
     * @param commitNotifier 事务提交通知器
     */
    public CommandService(
            CommandMapper commandMapper,
            OutboxMapper outboxMapper,
            OutboxCommitNotifier commitNotifier
    ) {
        this.commandMapper = commandMapper;
        this.outboxMapper = outboxMapper;
        this.commitNotifier = commitNotifier;
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
        if (!LOW_RISK_TEMPLATE.equals(normalizedTemplate)) {
            throw new IllegalArgumentException("低风险命令模板未发布: " + normalizedTemplate);
        }
        String normalizedKey = requireText(idempotencyKey, 128, "幂等键");
        String normalizedTraceId = requireText(traceId, 64, "链路追踪标识");
        if (!commandMapper.isEnabledDevice(deviceId)) {
            throw new IllegalArgumentException("目标设备不存在或未启用");
        }

        Instant now = clock.instant();
        CommandInstance queued = CommandInstance.createLowRisk(
                idGenerator.next(), deviceId, normalizedTemplate,
                Objects.requireNonNull(parameters, "命令参数不能为空"),
                normalizedKey, createdBy, now, ttlSeconds, timeoutSeconds
        ).transitionTo(CommandStatus.QUEUED, now);
        int inserted = commandMapper.insert(queued, writeJson(queued.parameters()));
        if (inserted == 0) {
            return loadByIdempotencyKey(createdBy, normalizedKey);
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
