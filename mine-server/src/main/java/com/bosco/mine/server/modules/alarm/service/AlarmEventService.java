package com.bosco.mine.server.modules.alarm.service;

import cn.dev33.satoken.stp.StpUtil;
import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.common.time.MineClock;
import com.bosco.mine.messaging.alarm.AlarmRaisedPayload;
import com.bosco.mine.messaging.alarm.AlarmRecoveredPayload;
import com.bosco.mine.server.modules.alarm.AlarmMetrics;
import com.bosco.mine.server.modules.alarm.AlarmMetrics.DispositionAction;
import com.bosco.mine.server.modules.alarm.AlarmMetrics.DispositionOutcome;
import com.bosco.mine.server.bootstrap.web.PageResponse;
import com.bosco.mine.server.modules.alarm.domain.AlarmStatus;
import com.bosco.mine.server.modules.alarm.dto.AlarmEventResponse;
import com.bosco.mine.server.modules.alarm.messaging.InvalidAlarmEventMessageException;
import com.bosco.mine.server.modules.alarm.repository.AlarmEventRepository;
import com.bosco.mine.server.modules.alarm.repository.AlarmEventRepository.AlarmEventRecord;
import com.bosco.mine.server.modules.alarm.repository.AlarmEventRepository.AlarmQuery;
import com.bosco.mine.server.modules.mine.service.MineScopeAuthorizer;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 聚合可靠告警事件，并提供可审计、幂等的确认和关闭闭环。
 */
@Service
public class AlarmEventService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AlarmEventService.class);
    private static final Set<String> SEVERITIES = Set.of("INFO", "WARNING", "CRITICAL");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private final AlarmEventRepository repository;
    private final OperationAuditService auditService;
    private final MineClock clock;
    private final IdGenerator idGenerator;
    private final AlarmMetrics metrics;
    private final MineScopeAuthorizer mineScopeAuthorizer;
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    /**
     * 创建生产环境告警服务。
     *
     * @param repository 告警仓储
     * @param auditService 操作审计服务
     * @param metrics 告警运行指标
     * @param mineScopeAuthorizer 矿区作用域授权器
     */
    @Autowired
    public AlarmEventService(
            AlarmEventRepository repository,
            OperationAuditService auditService,
            AlarmMetrics metrics,
            MineScopeAuthorizer mineScopeAuthorizer
    ) {
        this(repository, auditService, MineClock.systemUtc(), IdGenerator.timeOrdered(), metrics,
                mineScopeAuthorizer);
    }

    /**
     * 创建可观测的告警服务测试实例。
     *
     * @param repository 告警仓储
     * @param auditService 操作审计服务
     * @param clock 业务时钟
     * @param idGenerator 标识生成器
     * @param metrics 告警运行指标
     * @param mineScopeAuthorizer 矿区作用域授权器
     */
    AlarmEventService(
            AlarmEventRepository repository,
            OperationAuditService auditService,
            MineClock clock,
            IdGenerator idGenerator,
            AlarmMetrics metrics,
            MineScopeAuthorizer mineScopeAuthorizer
    ) {
        this.repository = Objects.requireNonNull(repository, "告警仓储不能为空");
        this.auditService = Objects.requireNonNull(auditService, "操作审计服务不能为空");
        this.clock = Objects.requireNonNull(clock, "告警业务时钟不能为空");
        this.idGenerator = Objects.requireNonNull(idGenerator, "告警标识生成器不能为空");
        this.metrics = Objects.requireNonNull(metrics, "告警运行指标不能为空");
        this.mineScopeAuthorizer = Objects.requireNonNull(mineScopeAuthorizer, "矿区作用域授权器不能为空");
    }

    /**
     * 幂等应用触发事件；恢复先到时只补齐触发证据，不回滚恢复事实。
     *
     * @param eventId 消息事件标识
     * @param payload 触发载荷
     * @return 聚合实际发生变化为真
     */
    @Transactional
    public boolean applyRaised(UUID eventId, AlarmRaisedPayload payload) {
        Objects.requireNonNull(eventId, "告警消息事件标识不能为空");
        Objects.requireNonNull(payload, "告警触发载荷不能为空");
        if (!repository.recordInbox(eventId)) {
            return false;
        }
        repository.lockOccurrence(payload.alarmOccurrenceId());
        validateDevice(payload.deviceId(), payload.productKey());
        String evidenceJson = writeJson(payload.evidence());
        AlarmEventRecord current = repository.lockById(payload.alarmOccurrenceId()).orElse(null);
        if (current == null) {
            repository.insertRaised(eventId, payload, evidenceJson, clock.instant());
            return true;
        }
        validateIdentity(current, payload.deviceId(), payload.productKey(), payload.ruleKey(),
                payload.ruleName(), payload.sourceType(), payload.severity(), payload.propertyId(),
                payload.policyRuntimeRevision(), payload.policySnapshotId(), payload.policyContentHash(),
                payload.raisedAt());
        if (current.raisedEventId() != null) {
            requireSameEvidence(current.raisedEvidenceJson(), evidenceJson, "触发");
            return false;
        }
        repository.supplementRaised(current.id(), eventId, evidenceJson, clock.instant());
        return true;
    }

    /**
     * 幂等应用恢复事件；触发缺席时依靠恢复载荷完整身份独立创建告警周期。
     *
     * @param eventId 消息事件标识
     * @param payload 恢复载荷
     * @return 聚合实际发生变化为真
     */
    @Transactional
    public boolean applyRecovered(UUID eventId, AlarmRecoveredPayload payload) {
        Objects.requireNonNull(eventId, "告警消息事件标识不能为空");
        Objects.requireNonNull(payload, "告警恢复载荷不能为空");
        if (!repository.recordInbox(eventId)) {
            return false;
        }
        repository.lockOccurrence(payload.alarmOccurrenceId());
        validateDevice(payload.deviceId(), payload.productKey());
        String evidenceJson = writeJson(payload.evidence());
        AlarmEventRecord current = repository.lockById(payload.alarmOccurrenceId()).orElse(null);
        if (current == null) {
            repository.insertRecovered(eventId, payload, evidenceJson, clock.instant());
            return true;
        }
        validateIdentity(current, payload.deviceId(), payload.productKey(), payload.ruleKey(),
                payload.ruleName(), payload.sourceType(), payload.severity(), payload.propertyId(),
                payload.policyRuntimeRevision(), payload.policySnapshotId(), payload.policyContentHash(),
                payload.raisedAt());
        if (current.recoveredEventId() != null) {
            if (!Objects.equals(current.recoveredAt(), payload.recoveredAt())
                    || !Objects.equals(current.recoveryReason(), payload.recoveryReason())) {
                throw new InvalidAlarmEventMessageException("同一告警周期出现冲突的恢复事实");
            }
            requireSameEvidence(current.recoveredEvidenceJson(), evidenceJson, "恢复");
            return false;
        }
        repository.applyRecovery(current.id(), eventId, payload.recoveredAt(), evidenceJson,
                payload.recoveryReason(), clock.instant());
        return true;
    }

    /**
     * 分页查询全局告警中心。
     */
    @Transactional(readOnly = true)
    public PageResponse<AlarmEventResponse> findGlobal(
            Boolean active,
            Boolean closed,
            AlarmStatus status,
            String severity,
            UUID deviceId,
            String deviceKeyword,
            String ruleKey,
            Instant from,
            Instant to,
            int page,
            int size
    ) {
        return find(null, active, closed, status, severity, deviceId, deviceKeyword,
                ruleKey, from, to, page, size);
    }

    /**
     * 分页查询告警触发时归属指定矿区的车载设备告警。
     *
     * @param siteId 矿区标识
     * @return 矿区车载设备告警分页
     */
    @Transactional(readOnly = true)
    public PageResponse<AlarmEventResponse> findForSite(
            UUID siteId,
            Boolean active,
            Boolean closed,
            AlarmStatus status,
            String severity,
            UUID deviceId,
            String deviceKeyword,
            String ruleKey,
            Instant from,
            Instant to,
            int page,
            int size
    ) {
        mineScopeAuthorizer.requireSite(Objects.requireNonNull(siteId, "矿区标识不能为空"));
        return find(siteId, active, closed, status, severity, deviceId, deviceKeyword,
                ruleKey, from, to, page, size);
    }

    private PageResponse<AlarmEventResponse> find(
            UUID siteId,
            Boolean active,
            Boolean closed,
            AlarmStatus status,
            String severity,
            UUID deviceId,
            String deviceKeyword,
            String ruleKey,
            Instant from,
            Instant to,
            int page,
            int size
    ) {
        if (page < 1 || page > 1_000_000 || size < 1 || size > 200) {
            throw error("ALARM-400-001", "告警分页参数不合法");
        }
        String normalizedSeverity = normalizeSeverity(severity);
        String normalizedDeviceKeyword = normalizeOptional(deviceKeyword, 128, "设备关键词");
        String normalizedRuleKey = normalizeOptional(ruleKey, 64, "规则编码");
        if (from != null && to != null && !from.isBefore(to)) {
            throw error("ALARM-400-002", "告警查询开始时间必须早于结束时间");
        }
        AlarmQuery query = new AlarmQuery(
                siteId, active, closed, status, normalizedSeverity, deviceId,
                normalizedDeviceKeyword, normalizedRuleKey, from, to);
        return new PageResponse<>(repository.find(query, page, size).stream()
                .map(this::toResponse)
                .toList(), page, size, repository.count(query));
    }

    /** 读取单个告警周期详情。 */
    @Transactional(readOnly = true)
    public AlarmEventResponse findById(UUID alarmId) {
        return toResponse(repository.findById(requireAlarmId(alarmId)).orElseThrow(this::notFound));
    }

    /**
     * 幂等确认告警。首次确认写入独立事实和审计；之后不因不同幂等键重复审计。
     */
    @Transactional
    public AlarmEventResponse acknowledgeCurrentUser(
            UUID alarmId,
            String note,
            String idempotencyKey,
            long expectedVersion
    ) {
        return acknowledge(alarmId, currentUserId(), note, idempotencyKey, expectedVersion);
    }

    /**
     * 使用明确操作人确认告警，供事务测试和受控内部调用复用。
     */
    @Transactional
    public AlarmEventResponse acknowledge(
            UUID alarmId,
            UUID operatorId,
            String note,
            String idempotencyKey,
            long expectedVersion
    ) {
        try {
            return acknowledgeInternal(alarmId, operatorId, note, idempotencyKey, expectedVersion);
        } catch (MineException exception) {
            observeDisposition(DispositionAction.ACKNOWLEDGE, classify(exception));
            throw exception;
        } catch (RuntimeException exception) {
            observeDisposition(DispositionAction.ACKNOWLEDGE, DispositionOutcome.FAILED);
            throw exception;
        }
    }

    private AlarmEventResponse acknowledgeInternal(
            UUID alarmId,
            UUID operatorId,
            String note,
            String idempotencyKey,
            long expectedVersion
    ) {
        UUID normalizedAlarmId = requireAlarmId(alarmId);
        UUID normalizedOperator = Objects.requireNonNull(operatorId, "告警确认人不能为空");
        String normalizedNote = normalizeNote(note);
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        repository.lockOccurrence(normalizedAlarmId);
        AlarmEventRecord current = repository.lockById(normalizedAlarmId).orElseThrow(this::notFound);
        if (current.acknowledgedAt() != null) {
            AlarmEventResponse response = toResponse(current);
            observeDispositionAfterCommit(
                    DispositionAction.ACKNOWLEDGE, DispositionOutcome.DUPLICATE);
            return response;
        }
        requireVersion(current.version(), expectedVersion);
        Instant now = clock.instant();
        repository.insertAcknowledgement(
                idGenerator.next(), normalizedAlarmId, normalizedOperator,
                normalizedNote, normalizedKey, now);
        if (repository.acknowledge(
                normalizedAlarmId, normalizedOperator, normalizedNote, now, expectedVersion) != 1) {
            throw conflict();
        }
        auditService.recordSuccess(
                "ALARM", "ACKNOWLEDGE", "alarm:event:ack", "ALARM_EVENT", normalizedAlarmId,
                Map.of("status", AlarmStatus.derive(
                        current.recoveredAt(), current.acknowledgedAt(), current.closedAt()).name(),
                        "version", current.version()),
                Map.of("acknowledged", true, "version", current.version() + 1)
        );
        AlarmEventResponse response = toResponse(
                repository.findById(normalizedAlarmId).orElseThrow(this::notFound));
        observeDispositionAfterCommit(
                DispositionAction.ACKNOWLEDGE, DispositionOutcome.APPLIED);
        return response;
    }

    /**
     * 幂等关闭已经恢复的告警；活动告警不能被关闭以避免掩盖现场风险。
     */
    @Transactional
    public AlarmEventResponse closeCurrentUser(
            UUID alarmId,
            String note,
            String idempotencyKey,
            long expectedVersion
    ) {
        return close(alarmId, currentUserId(), note, idempotencyKey, expectedVersion);
    }

    /**
     * 使用明确操作人关闭告警，供事务测试和受控内部调用复用。
     */
    @Transactional
    public AlarmEventResponse close(
            UUID alarmId,
            UUID operatorId,
            String note,
            String idempotencyKey,
            long expectedVersion
    ) {
        try {
            return closeInternal(alarmId, operatorId, note, idempotencyKey, expectedVersion);
        } catch (MineException exception) {
            observeDisposition(DispositionAction.CLOSE, classify(exception));
            throw exception;
        } catch (RuntimeException exception) {
            observeDisposition(DispositionAction.CLOSE, DispositionOutcome.FAILED);
            throw exception;
        }
    }

    private AlarmEventResponse closeInternal(
            UUID alarmId,
            UUID operatorId,
            String note,
            String idempotencyKey,
            long expectedVersion
    ) {
        UUID normalizedAlarmId = requireAlarmId(alarmId);
        UUID normalizedOperator = Objects.requireNonNull(operatorId, "告警关闭人不能为空");
        String normalizedNote = normalizeNote(note);
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        repository.lockOccurrence(normalizedAlarmId);
        AlarmEventRecord current = repository.lockById(normalizedAlarmId).orElseThrow(this::notFound);
        if (current.closedAt() != null) {
            AlarmEventResponse response = toResponse(current);
            observeDispositionAfterCommit(DispositionAction.CLOSE, DispositionOutcome.DUPLICATE);
            return response;
        }
        if (current.recoveredAt() == null) {
            throw error("ALARM-409-002", "活动告警恢复后才能关闭");
        }
        requireVersion(current.version(), expectedVersion);
        Instant now = clock.instant();
        if (repository.close(
                normalizedAlarmId, normalizedOperator, normalizedNote, normalizedKey,
                now, expectedVersion) != 1) {
            throw conflict();
        }
        auditService.recordSuccess(
                "ALARM", "CLOSE", "alarm:event:close", "ALARM_EVENT", normalizedAlarmId,
                Map.of("status", AlarmStatus.derive(
                        current.recoveredAt(), current.acknowledgedAt(), current.closedAt()).name(),
                        "version", current.version()),
                Map.of("status", AlarmStatus.CLOSED.name(), "version", current.version() + 1)
        );
        AlarmEventResponse response = toResponse(
                repository.findById(normalizedAlarmId).orElseThrow(this::notFound));
        observeDispositionAfterCommit(DispositionAction.CLOSE, DispositionOutcome.APPLIED);
        return response;
    }

    private void validateDevice(UUID deviceId, String productKey) {
        String actualProduct = repository.findDeviceProductKey(deviceId)
                .orElseThrow(() -> new InvalidAlarmEventMessageException("告警事件引用了不存在的设备"));
        if (!actualProduct.equals(productKey)) {
            throw new InvalidAlarmEventMessageException("告警事件产品编码与设备台账不一致");
        }
    }

    private void validateIdentity(
            AlarmEventRecord current,
            UUID deviceId,
            String productKey,
            String ruleKey,
            String ruleName,
            String sourceType,
            String severity,
            String propertyId,
            long policyRevision,
            UUID snapshotId,
            String contentHash,
            Instant raisedAt
    ) {
        if (!Objects.equals(current.deviceId(), deviceId)
                || !Objects.equals(current.productKey(), productKey)
                || !Objects.equals(current.ruleKey(), ruleKey)
                || !Objects.equals(current.ruleName(), ruleName)
                || !Objects.equals(current.sourceType(), sourceType)
                || !Objects.equals(current.severity(), severity)
                || !Objects.equals(current.propertyId(), propertyId)
                || current.policyRuntimeRevision() != policyRevision
                || !Objects.equals(current.policySnapshotId(), snapshotId)
                || !Objects.equals(current.policyContentHash(), contentHash)
                || !Objects.equals(current.raisedAt(), raisedAt)) {
            throw new InvalidAlarmEventMessageException("同一告警周期出现冲突的身份事实");
        }
    }

    private void requireSameEvidence(String existingJson, String incomingJson, String factName) {
        try {
            JsonNode existing = jsonMapper.readTree(existingJson);
            JsonNode incoming = jsonMapper.readTree(incomingJson);
            if (!existing.equals(incoming)) {
                throw new InvalidAlarmEventMessageException("同一告警周期出现冲突的" + factName + "证据");
            }
        } catch (JacksonException exception) {
            throw new IllegalStateException("数据库中的告警证据无法解析", exception);
        }
    }

    private AlarmEventResponse toResponse(AlarmEventRecord record) {
        return new AlarmEventResponse(
                record.id(), record.deviceId(), record.deviceCode(), record.deviceName(),
                record.productKey(), record.ruleKey(), record.ruleName(), record.sourceType(),
                record.severity(), record.propertyId(), AlarmStatus.derive(
                        record.recoveredAt(), record.acknowledgedAt(), record.closedAt()),
                record.policyRuntimeRevision(), record.policySnapshotId(), record.policyContentHash(),
                record.raisedAt(), readMap(record.raisedEvidenceJson()), record.recoveredAt(),
                readMap(record.recoveredEvidenceJson()), record.recoveryReason(), record.acknowledgedAt(),
                record.acknowledgedBy(), record.acknowledgementNote(), record.closedAt(),
                record.closedBy(), record.closeNote(), record.version()
        );
    }

    private String writeJson(Object value) {
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new InvalidAlarmEventMessageException("告警证据无法序列化", exception);
        }
    }

    private Map<String, Object> readMap(String json) {
        if (json == null) {
            return Map.of();
        }
        try {
            return jsonMapper.readValue(json, new TypeReference<>() { });
        } catch (JacksonException exception) {
            throw new IllegalStateException("数据库中的告警证据无法解析", exception);
        }
    }

    private String normalizeSeverity(String severity) {
        String normalized = normalizeOptional(severity, 16, "告警等级");
        if (normalized != null && !SEVERITIES.contains(normalized)) {
            throw error("ALARM-400-003", "告警等级不受支持");
        }
        return normalized;
    }

    private String normalizeOptional(String value, int maxLength, String field) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw error("ALARM-400-004", field + "不合法");
        }
        return normalized;
    }

    private String normalizeNote(String note) {
        if (note == null || note.strip().isEmpty() || note.strip().length() > 1000) {
            throw error("ALARM-400-005", "告警处置说明不能为空且不能超过一千字符");
        }
        return note.strip();
    }

    private String normalizeIdempotencyKey(String key) {
        if (key == null || !IDEMPOTENCY_KEY.matcher(key.strip()).matches()) {
            throw error("ALARM-400-006", "告警处置幂等键不合法");
        }
        return key.strip();
    }

    private UUID requireAlarmId(UUID alarmId) {
        return Objects.requireNonNull(alarmId, "告警标识不能为空");
    }

    private UUID currentUserId() {
        return UUID.fromString(StpUtil.getLoginIdAsString());
    }

    private void requireVersion(long actual, long expected) {
        if (actual != expected) {
            throw conflict();
        }
    }

    private MineException notFound() {
        return error("ALARM-404-001", "告警不存在");
    }

    private MineException conflict() {
        return error("ALARM-409-001", "告警已被其他操作更新，请刷新后重试");
    }

    private MineException error(String code, String message) {
        return new MineException(new ErrorCode(code, message), Map.of());
    }

    private DispositionOutcome classify(MineException exception) {
        String code = exception.errorCode().code();
        if (code.startsWith("ALARM-404-")) {
            return DispositionOutcome.NOT_FOUND;
        }
        if ("ALARM-409-001".equals(code)) {
            return DispositionOutcome.CONFLICT;
        }
        if (code.startsWith("ALARM-400-") || "ALARM-409-002".equals(code)) {
            return DispositionOutcome.INVALID;
        }
        return DispositionOutcome.FAILED;
    }

    private void observeDisposition(DispositionAction action, DispositionOutcome outcome) {
        try {
            metrics.recordDisposition(action, outcome);
        } catch (RuntimeException exception) {
            LOGGER.warn("记录告警处置指标失败，不改变既定业务结果", exception);
        }
    }

    private void observeDispositionAfterCommit(
            DispositionAction action,
            DispositionOutcome outcome
    ) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            observeDisposition(action, outcome);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                observeDisposition(action, outcome);
            }

            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    observeDisposition(action, DispositionOutcome.FAILED);
                }
            }
        });
    }
}
