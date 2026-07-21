package com.bosco.mine.server.modules.mine.operation.runtime;

import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.server.modules.mine.operation.domain.HaulCycleStateMachine;
import com.bosco.mine.server.modules.mine.operation.domain.HaulCycleStateMachine.CandidateReason;
import com.bosco.mine.server.modules.mine.operation.domain.HaulCycleStateMachine.CycleState;
import com.bosco.mine.server.modules.mine.operation.domain.HaulCycleStateMachine.CycleStatus;
import com.bosco.mine.server.modules.mine.operation.domain.HaulCycleStateMachine.Evaluation;
import com.bosco.mine.server.modules.mine.operation.domain.HaulCycleStateMachine.Evidence;
import com.bosco.mine.server.modules.mine.operation.domain.HaulCycleStateMachine.EvidenceRole;
import com.bosco.mine.server.modules.mine.operation.domain.HaulCycleStateMachine.Outcome;
import com.bosco.mine.server.modules.mine.operation.domain.HaulCycleStateMachine.SpatialSignal;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 在单一数据库事务内完成空间事件匹配、运输状态推进和执行事实固化。
 */
@Service
public class OperationRuntimeCoordinator {

    private static final TypeReference<Map<String, Evidence>> EVIDENCE_TYPE =
            new TypeReference<>() { };

    private final OperationRuntimeRepository repository;
    private final OperationEventQueue eventQueue;
    private final OperationAuditService auditService;
    private final OperationMetrics metrics;
    private final HaulCycleStateMachine stateMachine = new HaulCycleStateMachine();
    private final IdGenerator idGenerator = IdGenerator.timeOrdered();
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    /**
     * 创建运输作业运行协调器。
     *
     * @param repository 运输运行仓储
     * @param eventQueue 持久空间事件队列
     * @param auditService 不可变操作审计服务
     * @param metrics 生产运营运行指标
     */
    @Autowired
    public OperationRuntimeCoordinator(
            OperationRuntimeRepository repository,
            OperationEventQueue eventQueue,
            OperationAuditService auditService,
            OperationMetrics metrics
    ) {
        this.repository = Objects.requireNonNull(repository, "运输运行仓储不能为空");
        this.eventQueue = Objects.requireNonNull(eventQueue, "空间事件队列不能为空");
        this.auditService = Objects.requireNonNull(auditService, "运营审计服务不能为空");
        this.metrics = Objects.requireNonNull(metrics, "生产运营指标不能为空");
    }

    /**
     * 创建供独立集成测试和事务恢复验证使用的兼容协调器。
     *
     * @param repository 运输运行仓储
     * @param eventQueue 持久空间事件队列
     * @param auditService 不可变操作审计服务
     */
    public OperationRuntimeCoordinator(
            OperationRuntimeRepository repository,
            OperationEventQueue eventQueue,
            OperationAuditService auditService
    ) {
        this(repository, eventQueue, auditService, OperationMetrics.noop());
    }

    /**
     * 处理一个已认领事件，所有业务写入与队列完成必须原子提交。
     *
     * @param event 已认领空间事件
     * @param stepTimeout 单个运输步骤最大持续时间
     * @return 自动推进、候选或重复结果
     */
    @Transactional
    public ProcessResult process(
            OperationEventQueue.ClaimedEvent event,
            Duration stepTimeout
    ) {
        Objects.requireNonNull(event, "待处理空间事件不能为空");
        requireStepTimeout(stepTimeout);
        OperationRuntimeRepository.MatchSelection selection =
                repository.lockSelection(event);
        if (selection.vehicleResolution().status()
                != OperationRuntimeRepository.IdentityStatus.MATCHED) {
            return candidate(
                    event, null, "VEHICLE_MISMATCH",
                    selection.vehicleResolution().status().name(), List.of(), null);
        }
        List<OperationRuntimeRepository.AssignmentMatch> matches = selection.matches();
        if (matches.size() != 1) {
            return candidate(
                    event, null, "AMBIGUOUS_MATCH", "NO_UNIQUE_ACTIVE_ASSIGNMENT",
                    matches, null);
        }
        OperationRuntimeRepository.AssignmentMatch match = matches.getFirst();
        if (!"OPEN".equals(match.shiftStatus())
                || !withinActualShift(match, event.occurredAt())) {
            return candidate(
                    event, match, "CLOSED_SHIFT", "SHIFT_NOT_OPEN_AT_EVENT_TIME",
                    matches, null);
        }
        if (!List.of("RUNNING", "PAUSED").contains(match.workOrderStatus())) {
            return candidate(
                    event, match, "AMBIGUOUS_MATCH", "WORK_ORDER_NOT_ACTIVE",
                    matches, null);
        }
        if ("PAUSED".equals(match.workOrderStatusAtEvent())) {
            return candidate(
                    event, match, "PAUSED_WORK_ORDER", "WORK_ORDER_PAUSED_AT_EVENT_TIME",
                    matches, null);
        }
        if (!"RUNNING".equals(match.workOrderStatusAtEvent())) {
            return candidate(
                    event, match, "AMBIGUOUS_MATCH", "WORK_ORDER_NOT_RUNNING_AT_EVENT_TIME",
                    matches, null);
        }

        RuntimeContext runtime = lockOrCreateRuntime(match);
        if (event.tooLate()) {
            return candidate(event, match, "OUT_OF_ORDER", "TOO_LATE", matches, runtime);
        }
        CycleState current = runtime.state();
        if (isStepTimeout(current, event.occurredAt(), stepTimeout)) {
            return candidate(
                    event, match, "UNEXPECTED_EVENT", "STEP_TIMEOUT", matches, runtime);
        }
        Evaluation evaluation = stateMachine.evaluate(current, new SpatialSignal(
                event.eventId(), match.vehicleId(), event.payload().zoneVersionId(),
                HaulCycleStateMachine.EventType.valueOf(event.payload().eventType().name()),
                event.occurredAt(), event.ingestSequence()));
        if (evaluation.outcome() == Outcome.DUPLICATE) {
            completeEvent(event);
            return ProcessResult.DUPLICATE;
        }
        if (evaluation.outcome() == Outcome.CANDIDATE) {
            CandidateReason reason = evaluation.candidateReason();
            return candidate(
                    event, match, reason.name(), reason.name(), matches, runtime);
        }
        return transition(event, match, runtime, evaluation);
    }

    private ProcessResult transition(
            OperationEventQueue.ClaimedEvent event,
            OperationRuntimeRepository.AssignmentMatch match,
            RuntimeContext runtime,
            Evaluation evaluation
    ) {
        CycleState next = evaluation.state();
        Instant startedAt = evaluation.acceptedEvidence() == EvidenceRole.LOAD_ENTER
                ? event.occurredAt() : runtime.startedAt();
        if (next.status() != CycleStatus.COMPLETED) {
            repository.updateRuntimeState(
                    runtime.id(), next, event.eventId(), evidenceJson(next.evidence()), startedAt);
            UUID outboxId = idGenerator.next();
            repository.insertOutbox(
                    outboxId, match.organizationId(), match.siteId(),
                    "WORK_ORDER", match.workOrderId(), canonicalJson(runtimePayload(
                            "RUNTIME_TRANSITIONED", event, match, next, null)));
            auditTransition(match, runtime.state(), next, event.eventId());
            completeEvent(event);
            metrics.stage(OperationMetrics.Stage.AUTOMATION,
                    OperationMetrics.Result.SUCCESS, OperationMetrics.Reason.NONE);
            return ProcessResult.TRANSITIONED;
        }

        OperationRuntimeRepository.CompletedRecord record = completedRecord(
                match, next, startedAt);
        repository.insertCompletedRecord(record);
        for (EvidenceRole role : EvidenceRole.values()) {
            Evidence evidence = next.evidence().get(role);
            if (evidence != null) {
                repository.insertEvidence(
                        idGenerator.next(), record, evidence,
                        repository.findSpatialPayload(evidence.eventId()));
            }
        }
        repository.insertEffectiveRecord(record);
        CycleState reset = stateMachine.nextCycle(next);
        repository.updateRuntimeState(
                runtime.id(), reset, event.eventId(), evidenceJson(reset.evidence()), null);
        repository.insertOutbox(
                idGenerator.next(), match.organizationId(), match.siteId(),
                "OPERATION_RECORD", record.id(), canonicalJson(runtimePayload(
                        "RECORD_COMPLETED", event, match, reset, record.id())));
        auditCompleted(match, record, event.eventId());
        completeEvent(event);
        metrics.stage(OperationMetrics.Stage.AUTOMATION,
                OperationMetrics.Result.SUCCESS, OperationMetrics.Reason.NONE);
        metrics.record(OperationMetrics.WorkType.HAULAGE,
                OperationMetrics.Result.SUCCESS);
        return ProcessResult.RECORD_COMPLETED;
    }

    private OperationRuntimeRepository.CompletedRecord completedRecord(
            OperationRuntimeRepository.AssignmentMatch match,
            CycleState completed,
            Instant startedAt
    ) {
        Evidence loadDwell = completed.evidence().get(EvidenceRole.LOAD_DWELL);
        Evidence unloadEnter = completed.evidence().get(EvidenceRole.UNLOAD_ENTER);
        Evidence unloadDwell = completed.evidence().get(EvidenceRole.UNLOAD_DWELL);
        Map<String, Object> snapshot = new TreeMap<>();
        snapshot.put("assignmentId", match.assignmentId().toString());
        snapshot.put("cycleNumber", completed.cycleNumber());
        snapshot.put("driverPersonId", match.driverPersonId() == null
                ? null : match.driverPersonId().toString());
        snapshot.put("evidence", completed.evidence());
        snapshot.put("loadGeofenceVersionId", match.loadGeofenceVersionId().toString());
        snapshot.put("organizationId", match.organizationId().toString());
        snapshot.put("releaseSnapshot", parseJson(match.releaseSnapshotJson()));
        snapshot.put("routeVersionId", match.routeVersionId().toString());
        snapshot.put("shiftInstanceId", match.shiftInstanceId().toString());
        snapshot.put("siteId", match.siteId().toString());
        snapshot.put("unloadGeofenceVersionId", match.unloadGeofenceVersionId().toString());
        snapshot.put("vehicleId", match.vehicleId().toString());
        snapshot.put("workOrderId", match.workOrderId().toString());
        return new OperationRuntimeRepository.CompletedRecord(
                idGenerator.next(), match, completed.cycleNumber(), startedAt,
                loadDwell.occurredAt(), unloadEnter.occurredAt(),
                unloadDwell.occurredAt(), canonicalJson(snapshot));
    }

    private ProcessResult candidate(
            OperationEventQueue.ClaimedEvent event,
            OperationRuntimeRepository.AssignmentMatch match,
            String candidateType,
            String reasonCode,
            List<OperationRuntimeRepository.AssignmentMatch> matches,
            RuntimeContext runtime
    ) {
        UUID candidateId = idGenerator.next();
        boolean inserted = repository.insertCandidate(
                candidateId, event, match, candidateType, reasonCode,
                canonicalJson(candidatePayload(event, matches, runtime)));
        if (inserted) {
            repository.insertOutbox(
                    idGenerator.next(), event.organizationId(), event.siteId(),
                    "OPERATION_CANDIDATE", candidateId,
                    canonicalJson(candidateOutboxPayload(
                            candidateId, event, candidateType, reasonCode, match)));
            auditCandidate(candidateId, event, candidateType, reasonCode, match);
            metrics.candidate(candidateType, reasonCode);
        }
        completeEvent(event);
        return ProcessResult.CANDIDATE;
    }

    private RuntimeContext lockOrCreateRuntime(
            OperationRuntimeRepository.AssignmentMatch match
    ) {
        return repository.lockRuntimeState(match.assignmentId())
                .map(row -> new RuntimeContext(
                        row.id(), restoreState(row, match), row.startedAt()))
                .orElseGet(() -> {
                    UUID id = idGenerator.next();
                    repository.insertInitialRuntimeState(id, match);
                    return new RuntimeContext(
                            id,
                            CycleState.initial(
                                    match.vehicleId(), match.loadGeofenceVersionId(),
                                    match.unloadGeofenceVersionId()),
                            null);
                });
    }

    private CycleState restoreState(
            OperationRuntimeRepository.RuntimeState row,
            OperationRuntimeRepository.AssignmentMatch match
    ) {
        EnumMap<EvidenceRole, Evidence> evidence = new EnumMap<>(EvidenceRole.class);
        try {
            jsonMapper.readValue(row.evidenceSnapshotJson(), EVIDENCE_TYPE)
                    .forEach((role, item) -> evidence.put(EvidenceRole.valueOf(role), item));
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new IllegalStateException("持久运输循环证据快照无法恢复", exception);
        }
        return new CycleState(
                CycleStatus.valueOf(row.cycleStatus()), row.cycleNumber(), match.vehicleId(),
                match.loadGeofenceVersionId(), match.unloadGeofenceVersionId(),
                row.lastOccurredAt(), row.lastIngestSequence(), evidence);
    }

    private boolean withinActualShift(
            OperationRuntimeRepository.AssignmentMatch match,
            Instant occurredAt
    ) {
        return match.shiftActualStartAt() != null
                && !occurredAt.isBefore(match.shiftActualStartAt())
                && (match.shiftActualEndAt() == null
                    || occurredAt.isBefore(match.shiftActualEndAt()));
    }

    private boolean isStepTimeout(
            CycleState state,
            Instant occurredAt,
            Duration stepTimeout
    ) {
        return state.status() != CycleStatus.WAITING_LOAD
                && state.lastOccurredAt() != null
                && occurredAt.isAfter(state.lastOccurredAt())
                && Duration.between(state.lastOccurredAt(), occurredAt)
                        .compareTo(stepTimeout) > 0;
    }

    private void completeEvent(OperationEventQueue.ClaimedEvent event) {
        if (!eventQueue.complete(event)) {
            throw new LeaseLostException(event.eventId());
        }
    }

    private void requireStepTimeout(Duration value) {
        if (value == null || value.isZero() || value.isNegative()
                || value.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("运输步骤超时必须在七天以内");
        }
    }

    private Map<String, Object> candidatePayload(
            OperationEventQueue.ClaimedEvent event,
            List<OperationRuntimeRepository.AssignmentMatch> matches,
            RuntimeContext runtime
    ) {
        Map<String, Object> payload = new TreeMap<>();
        payload.put("event", event.payload());
        payload.put("matchCount", matches.size());
        payload.put("matches", matches.stream().map(this::matchSummary).toList());
        payload.put("queueId", event.queueId().toString());
        payload.put("tooLate", event.tooLate());
        if (runtime != null) {
            payload.put("runtimeState", runtime.state());
        }
        return payload;
    }

    private Map<String, Object> candidateOutboxPayload(
            UUID candidateId,
            OperationEventQueue.ClaimedEvent event,
            String candidateType,
            String reasonCode,
            OperationRuntimeRepository.AssignmentMatch match
    ) {
        Map<String, Object> payload = new TreeMap<>();
        payload.put("candidateId", candidateId.toString());
        payload.put("candidateType", candidateType);
        payload.put("eventId", event.eventId().toString());
        payload.put("occurredAt", event.occurredAt().toString());
        payload.put("reasonCode", reasonCode);
        payload.put("siteId", event.siteId().toString());
        payload.put("updateType", "CANDIDATE_CREATED");
        if (match != null) {
            payload.put("assignmentId", match.assignmentId().toString());
            payload.put("workOrderId", match.workOrderId().toString());
        }
        return payload;
    }

    private Map<String, Object> runtimePayload(
            String updateType,
            OperationEventQueue.ClaimedEvent event,
            OperationRuntimeRepository.AssignmentMatch match,
            CycleState state,
            UUID recordId
    ) {
        Map<String, Object> payload = new TreeMap<>();
        payload.put("assignmentId", match.assignmentId().toString());
        payload.put("cycleNumber", state.cycleNumber());
        payload.put("cycleStatus", state.status().name());
        payload.put("eventId", event.eventId().toString());
        payload.put("occurredAt", event.occurredAt().toString());
        payload.put("siteId", match.siteId().toString());
        payload.put("updateType", updateType);
        payload.put("workOrderId", match.workOrderId().toString());
        if (recordId != null) {
            payload.put("operationRecordId", recordId.toString());
        }
        return payload;
    }

    private Map<String, Object> matchSummary(
            OperationRuntimeRepository.AssignmentMatch match
    ) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("assignmentId", match.assignmentId().toString());
        value.put("shiftInstanceId", match.shiftInstanceId().toString());
        value.put("shiftStatus", match.shiftStatus());
        value.put("workOrderId", match.workOrderId().toString());
        value.put("workOrderStatus", match.workOrderStatus());
        return value;
    }

    private String evidenceJson(Map<EvidenceRole, Evidence> evidence) {
        Map<String, Evidence> normalized = new TreeMap<>();
        evidence.forEach((role, item) -> normalized.put(role.name(), item));
        return canonicalJson(normalized);
    }

    private Object parseJson(String value) {
        try {
            return jsonMapper.readValue(value, Object.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("作业发布快照无法解析", exception);
        }
    }

    private String canonicalJson(Object value) {
        try {
            Object generic = jsonMapper.treeToValue(jsonMapper.valueToTree(value), Object.class);
            return jsonMapper.writeValueAsString(canonicalize(generic));
        } catch (JacksonException exception) {
            throw new IllegalStateException("运营运行事实无法规范化", exception);
        }
    }

    private Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(
                    String.valueOf(key), canonicalize(item)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::canonicalize).toList();
        }
        return value;
    }

    private void auditTransition(
            OperationRuntimeRepository.AssignmentMatch match,
            CycleState before,
            CycleState after,
            UUID eventId
    ) {
        auditService.recordSuccess(
                "MINE_OPERATION", "AUTO_ADVANCE_HAUL_CYCLE", "mine:operation:auto",
                "OPERATION_RUNTIME", match.assignmentId(),
                Map.of("cycleNumber", before.cycleNumber(), "cycleStatus", before.status().name()),
                Map.of("cycleNumber", after.cycleNumber(), "cycleStatus", after.status().name(),
                        "eventId", eventId.toString()));
    }

    private void auditCompleted(
            OperationRuntimeRepository.AssignmentMatch match,
            OperationRuntimeRepository.CompletedRecord record,
            UUID eventId
    ) {
        auditService.recordSuccess(
                "MINE_OPERATION", "AUTO_COMPLETE_HAUL_CYCLE", "mine:operation:auto",
                "OPERATION_RECORD", record.id(),
                Map.of("assignmentId", match.assignmentId().toString(),
                        "cycleNumber", record.cycleNumber()),
                Map.of("recordStatus", "COMPLETED", "eventId", eventId.toString()));
    }

    private void auditCandidate(
            UUID candidateId,
            OperationEventQueue.ClaimedEvent event,
            String candidateType,
            String reasonCode,
            OperationRuntimeRepository.AssignmentMatch match
    ) {
        Map<String, Object> after = new TreeMap<>();
        after.put("candidateType", candidateType);
        after.put("eventId", event.eventId().toString());
        after.put("reasonCode", reasonCode);
        if (match != null) {
            after.put("assignmentId", match.assignmentId().toString());
            after.put("workOrderId", match.workOrderId().toString());
        }
        auditService.recordSuccess(
                "MINE_OPERATION", "CREATE_OPERATION_CANDIDATE", "mine:operation:auto",
                "OPERATION_CANDIDATE", candidateId, Map.of(), after);
    }

    /** 空间事件业务处理结果。 */
    public enum ProcessResult {
        /** 运输循环推进到下一步骤。 */
        TRANSITIONED,
        /** 完整循环已经形成不可变执行事实。 */
        RECORD_COMPLETED,
        /** 事件需要人工确认。 */
        CANDIDATE,
        /** 事件已经成为当前运行态证据。 */
        DUPLICATE
    }

    /** 当前执行器已经失去空间事件处理租约。 */
    public static final class LeaseLostException extends IllegalStateException {
        /** 创建不暴露载荷的租约丢失异常。 */
        public LeaseLostException(UUID eventId) {
            super("运营空间事件租约已失效: " + eventId);
        }
    }

    private record RuntimeContext(UUID id, CycleState state, Instant startedAt) {
    }
}
