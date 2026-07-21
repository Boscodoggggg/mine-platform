package com.bosco.mine.server.modules.notification.service;

import cn.dev33.satoken.stp.StpUtil;
import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.server.modules.notification.dto.NotificationPolicyActionRequest;
import com.bosco.mine.server.modules.notification.dto.NotificationPolicyPublishRequest;
import com.bosco.mine.server.modules.notification.dto.NotificationPolicyRequest;
import com.bosco.mine.server.modules.notification.dto.NotificationPolicyResponse;
import com.bosco.mine.server.modules.notification.model.NotificationEventType;
import com.bosco.mine.server.modules.notification.model.NotificationPolicy;
import com.bosco.mine.server.modules.notification.model.NotificationPolicyDependencyStatus;
import com.bosco.mine.server.modules.notification.model.NotificationPolicyOverlapCandidate;
import com.bosco.mine.server.modules.notification.model.NotificationPolicyStep;
import com.bosco.mine.server.modules.notification.model.NotificationPolicyVersion;
import com.bosco.mine.server.modules.notification.repository.NotificationPolicyMapper;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 管理通知升级策略草稿、不可变发布快照和归档生命周期。
 */
@Service
public class NotificationPolicyService {

    private static final Set<String> SEVERITIES = Set.of("INFO", "WARNING", "CRITICAL");
    private static final List<String> SEVERITY_ORDER = List.of("INFO", "WARNING", "CRITICAL");
    private static final Set<String> TICKET_ACTIONS =
            Set.of("NONE", "CREATE", "ASSIGN", "ESCALATE");
    private static final int MAX_STEPS = 20;

    private static final ErrorCode POLICY_NOT_FOUND =
            new ErrorCode("NOTIFICATION-404-031", "通知升级策略不存在");
    private static final ErrorCode VERSION_NOT_FOUND =
            new ErrorCode("NOTIFICATION-404-032", "通知升级策略版本不存在");
    private static final ErrorCode POLICY_CONFLICT =
            new ErrorCode("NOTIFICATION-409-031", "通知升级策略已被其他管理员修改，请刷新后重试");
    private static final ErrorCode POLICY_DUPLICATE =
            new ErrorCode("NOTIFICATION-409-032", "通知升级策略编码已经存在");
    private static final ErrorCode POLICY_OVERLAP =
            new ErrorCode("NOTIFICATION-409-033", "同优先级已存在匹配范围重叠的发布策略");
    private static final ErrorCode POLICY_INVALID =
            new ErrorCode("NOTIFICATION-400-031", "通知升级策略配置不合法");

    private final NotificationPolicyMapper mapper;
    private final OperationAuditService auditService;
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator = IdGenerator.timeOrdered();

    /**
     * 创建通知升级策略服务。
     *
     * @param mapper 策略数据接口
     * @param auditService 操作审计服务
     * @param objectMapper JSON 映射器
     */
    public NotificationPolicyService(
            NotificationPolicyMapper mapper,
            OperationAuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.mapper = mapper;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询全部通知升级策略及当前草稿和发布版本。
     *
     * @return 策略列表
     */
    @Transactional(readOnly = true)
    public List<NotificationPolicyResponse> list() {
        return mapper.findAll().stream().map(this::loadResponse).toList();
    }

    /**
     * 查询指定通知升级策略。
     *
     * @param policyId 策略标识
     * @return 策略详情
     */
    @Transactional(readOnly = true)
    public NotificationPolicyResponse get(UUID policyId) {
        return loadResponse(requirePolicy(policyId));
    }

    /**
     * 创建策略根、第一版草稿及完整步骤。
     *
     * @param request 创建请求
     * @return 创建后的策略
     */
    @Transactional
    public NotificationPolicyResponse create(NotificationPolicyRequest request) {
        NormalizedPolicy normalized = normalize(request);
        if (mapper.findByCode(normalized.policyCode()).isPresent()) {
            throw duplicate(normalized.policyCode());
        }
        UUID policyId = idGenerator.next();
        UUID versionId = idGenerator.next();
        Instant now = Instant.now();
        NotificationPolicy policy = new NotificationPolicy(
                policyId, normalized.policyCode(), normalized.name(),
                normalized.description(), normalized.priority(), "ACTIVE",
                now, now, null, 0);
        NotificationPolicyVersion draft = new NotificationPolicyVersion(
                versionId, policyId, 1, "DRAFT",
                writeConditions(normalized.matchConditions()),
                writeJson(normalized.severityFilter()), normalized.stopOnAck(),
                normalized.notifyOnRecovery(), null, null, null, null, now);
        List<NotificationPolicyStep> steps = createSteps(
                versionId, normalized.steps(), now);
        try {
            if (mapper.insertPolicy(policy, currentOperatorId()) != 1
                    || mapper.insertVersion(draft, currentOperatorId()) != 1
                    || mapper.insertSteps(steps) != steps.size()) {
                throw new IllegalStateException("通知升级策略写入行数异常");
            }
        } catch (DuplicateKeyException exception) {
            throw duplicate(normalized.policyCode());
        }
        auditService.recordSuccess(
                "NOTIFICATION", "CREATE_NOTIFICATION_POLICY",
                "notification:policy:edit", "NOTIFICATION_POLICY",
                policyId, Map.of(), summary(policy, draft, steps));
        return toResponse(policy, draft, steps, null, List.of());
    }

    /**
     * 使用聚合乐观锁整体替换当前草稿。
     *
     * @param policyId 策略标识
     * @param request 修改请求
     * @return 修改后的策略
     */
    @Transactional
    public NotificationPolicyResponse updateDraft(
            UUID policyId,
            NotificationPolicyRequest request
    ) {
        NotificationPolicy existing = requireActiveLocked(policyId, request.version());
        if (!existing.policyCode().equals(request.policyCode())) {
            throw invalid("POLICY_CODE_IMMUTABLE");
        }
        NotificationPolicyVersion draft = mapper.findDraft(policyId)
                .orElseThrow(() -> invalid("DRAFT_NOT_FOUND"));
        List<NotificationPolicyStep> previousSteps = mapper.findSteps(draft.id());
        NormalizedPolicy normalized = normalize(request);
        Instant now = Instant.now();
        NotificationPolicy candidate = new NotificationPolicy(
                policyId, existing.policyCode(), normalized.name(), normalized.description(),
                normalized.priority(), existing.status(), existing.createdAt(), now,
                existing.archivedAt(), existing.version() + 1);
        NotificationPolicyVersion updatedDraft = new NotificationPolicyVersion(
                draft.id(), policyId, draft.versionNumber(), "DRAFT",
                writeConditions(normalized.matchConditions()),
                writeJson(normalized.severityFilter()), normalized.stopOnAck(),
                normalized.notifyOnRecovery(), null, null, null, null, draft.createdAt());
        List<NotificationPolicyStep> steps = createSteps(
                draft.id(), normalized.steps(), now);
        if (mapper.updatePolicy(candidate, request.version(), currentOperatorId()) != 1
                || mapper.updateDraft(updatedDraft) != 1) {
            throw conflict(policyId);
        }
        mapper.deleteDraftSteps(draft.id());
        if (mapper.insertSteps(steps) != steps.size()) {
            throw new IllegalStateException("通知升级策略草稿步骤写入行数异常");
        }
        NotificationPolicyVersion published = mapper.findPublished(policyId).orElse(null);
        List<NotificationPolicyStep> publishedSteps = published == null
                ? List.of() : mapper.findSteps(published.id());
        auditService.recordSuccess(
                "NOTIFICATION", "UPDATE_NOTIFICATION_POLICY_DRAFT",
                "notification:policy:edit", "NOTIFICATION_POLICY",
                policyId, summary(existing, draft, previousSteps),
                summary(candidate, updatedDraft, steps));
        return toResponse(candidate, updatedDraft, steps, published, publishedSteps);
    }

    /**
     * 校验依赖和冲突后原子发布草稿，并退役同策略旧版本。
     *
     * @param policyId 策略标识
     * @param request 发布请求
     * @return 发布后的策略
     */
    @Transactional
    public NotificationPolicyResponse publish(
            UUID policyId,
            NotificationPolicyPublishRequest request
    ) {
        NotificationPolicy policy = mapper.lockById(policyId)
                .orElseThrow(() -> notFound(policyId));
        NotificationPolicyVersion version = mapper.findVersionById(
                        policyId, request.versionId())
                .orElseThrow(() -> new MineException(
                        VERSION_NOT_FOUND, Map.of("versionId", request.versionId())));
        List<NotificationPolicyStep> steps = mapper.findSteps(version.id());
        if ("PUBLISHED".equals(version.state())) {
            return toResponse(policy, null, List.of(), version, steps);
        }
        if (!"ACTIVE".equals(policy.status())) {
            throw invalid("POLICY_ARCHIVED");
        }
        if (policy.version() != request.version()) {
            throw conflict(policyId);
        }
        if (!"DRAFT".equals(version.state())) {
            throw invalid("VERSION_NOT_DRAFT");
        }
        String reason = normalizeNullable(request.publicationReason());
        if (reason == null || reason.length() > 500) {
            throw invalid("PUBLICATION_REASON_INVALID");
        }
        validateStoredSteps(version, steps);
        validateDependencies(version, steps);
        validateOverlap(policy, version);
        Instant now = Instant.now();
        UUID operatorId = currentOperatorId();
        Optional<NotificationPolicyVersion> currentPublished = mapper.findPublished(policyId);
        currentPublished.ifPresent(current -> {
            if (mapper.retireVersion(current.id(), now, operatorId) != 1) {
                throw conflict(policyId);
            }
        });
        if (mapper.publishVersion(version.id(), now, operatorId, reason) != 1
                || mapper.touchPolicy(policyId, request.version(), operatorId) != 1) {
            throw conflict(policyId);
        }
        NotificationPolicyVersion published = new NotificationPolicyVersion(
                version.id(), policyId, version.versionNumber(), "PUBLISHED",
                version.matchConditionsJson(), version.severityFilterJson(),
                version.stopOnAck(), version.notifyOnRecovery(), now, operatorId,
                reason, null, version.createdAt());
        NotificationPolicy updated = new NotificationPolicy(
                policy.id(), policy.policyCode(), policy.name(), policy.description(),
                policy.priority(), policy.status(), policy.createdAt(), now,
                policy.archivedAt(), policy.version() + 1);
        auditService.recordSuccess(
                "NOTIFICATION", "PUBLISH_NOTIFICATION_POLICY",
                "notification:policy:publish", "NOTIFICATION_POLICY",
                policyId, summary(policy, version, steps),
                summary(updated, published, steps));
        return toResponse(updated, null, List.of(), published, steps);
    }

    /**
     * 从当前已发布快照派生可编辑的新草稿。
     *
     * @param policyId 策略标识
     * @param request 乐观锁动作请求
     * @return 包含新草稿的策略
     */
    @Transactional
    public NotificationPolicyResponse createDraft(
            UUID policyId,
            NotificationPolicyActionRequest request
    ) {
        NotificationPolicy policy = requireActiveLocked(policyId, request.version());
        Optional<NotificationPolicyVersion> existingDraft = mapper.findDraft(policyId);
        if (existingDraft.isPresent()) {
            NotificationPolicyVersion draft = existingDraft.orElseThrow();
            return toResponse(policy, draft, mapper.findSteps(draft.id()),
                    mapper.findPublished(policyId).orElse(null),
                    mapper.findPublished(policyId)
                            .map(value -> mapper.findSteps(value.id())).orElse(List.of()));
        }
        NotificationPolicyVersion published = mapper.findPublished(policyId)
                .orElseThrow(() -> invalid("PUBLISHED_VERSION_NOT_FOUND"));
        List<NotificationPolicyStep> publishedSteps = mapper.findSteps(published.id());
        UUID draftId = idGenerator.next();
        Instant now = Instant.now();
        NotificationPolicyVersion draft = new NotificationPolicyVersion(
                draftId, policyId, published.versionNumber() + 1, "DRAFT",
                published.matchConditionsJson(), published.severityFilterJson(),
                published.stopOnAck(), published.notifyOnRecovery(),
                null, null, null, null, now);
        List<NotificationPolicyStep> draftSteps = publishedSteps.stream()
                .map(step -> new NotificationPolicyStep(
                        idGenerator.next(), draftId, step.stepOrder(), step.delaySeconds(),
                        step.channelId(), step.recipientGroupId(), step.templateId(),
                        step.recoveryTemplateId(), step.ticketAction(),
                        step.ticketPriority(), now))
                .toList();
        UUID operatorId = currentOperatorId();
        if (mapper.insertVersion(draft, operatorId) != 1
                || mapper.insertSteps(draftSteps) != draftSteps.size()
                || mapper.touchPolicy(policyId, request.version(), operatorId) != 1) {
            throw conflict(policyId);
        }
        NotificationPolicy updated = new NotificationPolicy(
                policy.id(), policy.policyCode(), policy.name(), policy.description(),
                policy.priority(), policy.status(), policy.createdAt(), now,
                policy.archivedAt(), policy.version() + 1);
        auditService.recordSuccess(
                "NOTIFICATION", "CREATE_NOTIFICATION_POLICY_DRAFT",
                "notification:policy:edit", "NOTIFICATION_POLICY",
                policyId, summary(policy, published, publishedSteps),
                summary(updated, draft, draftSteps));
        return toResponse(updated, draft, draftSteps, published, publishedSteps);
    }

    /**
     * 使用乐观锁归档策略，使其不再参与后续告警匹配。
     *
     * @param policyId 策略标识
     * @param request 乐观锁动作请求
     * @return 归档后的策略
     */
    @Transactional
    public NotificationPolicyResponse archive(
            UUID policyId,
            NotificationPolicyActionRequest request
    ) {
        NotificationPolicy policy = requireActiveLocked(policyId, request.version());
        Instant now = Instant.now();
        UUID operatorId = currentOperatorId();
        if (mapper.archivePolicy(policyId, request.version(), now, operatorId) != 1) {
            throw conflict(policyId);
        }
        NotificationPolicy archived = new NotificationPolicy(
                policy.id(), policy.policyCode(), policy.name(), policy.description(),
                policy.priority(), "ARCHIVED", policy.createdAt(), now, now,
                policy.version() + 1);
        NotificationPolicyVersion draft = mapper.findDraft(policyId).orElse(null);
        NotificationPolicyVersion published = mapper.findPublished(policyId).orElse(null);
        List<NotificationPolicyStep> draftSteps = draft == null
                ? List.of() : mapper.findSteps(draft.id());
        List<NotificationPolicyStep> publishedSteps = published == null
                ? List.of() : mapper.findSteps(published.id());
        auditService.recordSuccess(
                "NOTIFICATION", "ARCHIVE_NOTIFICATION_POLICY",
                "notification:policy:edit", "NOTIFICATION_POLICY",
                policyId, Map.of("status", policy.status(), "version", policy.version()),
                Map.of("status", archived.status(), "version", archived.version()));
        return toResponse(archived, draft, draftSteps, published, publishedSteps);
    }

    private NormalizedPolicy normalize(NotificationPolicyRequest request) {
        if (request == null || request.policyCode() == null
                || !request.policyCode().matches("^[a-z][a-z0-9-]{1,63}$")
                || request.name() == null || request.name().isBlank()
                || request.name().length() > 128
                || request.priority() < 0 || request.priority() > 1000
                || request.matchConditions() == null
                || request.matchConditions().mineIds() == null
                || request.matchConditions().ruleKeys() == null
                || request.matchConditions().mineIds().size() > 100
                || request.matchConditions().ruleKeys().size() > 100
                || request.matchConditions().ruleKeys().stream().anyMatch(
                        key -> key == null || !key.matches("^[a-z][a-z0-9._-]{0,127}$"))
                || request.severityFilter() == null
                || request.severityFilter().isEmpty()
                || request.severityFilter().size() > 3
                || !SEVERITIES.containsAll(request.severityFilter())
                || request.steps() == null || request.steps().isEmpty()
                || request.steps().size() > MAX_STEPS) {
            throw invalid("POLICY_INVALID");
        }
        String description = normalizeNullable(request.description());
        if (description != null && description.length() > 500) {
            throw invalid("DESCRIPTION_TOO_LONG");
        }
        List<NotificationPolicyRequest.Step> steps = new ArrayList<>(request.steps());
        steps.sort(Comparator.comparingInt(NotificationPolicyRequest.Step::stepOrder));
        validateRequestedSteps(steps, request.notifyOnRecovery());
        List<String> severities = request.severityFilter().stream()
                .sorted(Comparator.comparingInt(SEVERITY_ORDER::indexOf)).toList();
        return new NormalizedPolicy(
                request.policyCode(), request.name().strip(), description,
                request.priority(), normalizeConditions(request.matchConditions()),
                severities, request.stopOnAck(), request.notifyOnRecovery(),
                List.copyOf(steps));
    }

    private void validateRequestedSteps(
            List<NotificationPolicyRequest.Step> steps,
            boolean notifyOnRecovery
    ) {
        int lastDelay = -1;
        Set<String> uniqueRoutes = new HashSet<>();
        for (int index = 0; index < steps.size(); index++) {
            NotificationPolicyRequest.Step step = steps.get(index);
            if (step == null || step.stepOrder() != index) {
                throw invalid("STEP_ORDER_INVALID");
            }
            if (step.delaySeconds() < lastDelay) {
                throw invalid("STEP_DELAY_NOT_MONOTONIC");
            }
            lastDelay = step.delaySeconds();
            if (step.delaySeconds() < 0 || step.delaySeconds() > 604800
                    || step.channelId() == null || step.recipientGroupId() == null
                    || step.templateId() == null || step.ticketAction() == null
                    || !TICKET_ACTIONS.contains(step.ticketAction())) {
                throw invalid("STEP_INVALID");
            }
            if (notifyOnRecovery && step.recoveryTemplateId() == null) {
                throw invalid("RECOVERY_TEMPLATE_REQUIRED");
            }
            if (!notifyOnRecovery && step.recoveryTemplateId() != null) {
                throw invalid("RECOVERY_TEMPLATE_NOT_ALLOWED");
            }
            boolean priorityRequired = "CREATE".equals(step.ticketAction())
                    || "ESCALATE".equals(step.ticketAction());
            if (priorityRequired
                    && (step.ticketPriority() == null || step.ticketPriority() < 1
                    || step.ticketPriority() > 100)) {
                throw invalid("TICKET_PRIORITY_REQUIRED");
            }
            if (!priorityRequired && step.ticketPriority() != null) {
                throw invalid("TICKET_PRIORITY_NOT_ALLOWED");
            }
            String route = step.channelId() + "|" + step.recipientGroupId()
                    + "|" + step.templateId() + "|" + step.delaySeconds();
            if (!uniqueRoutes.add(route)) {
                throw invalid("STEP_DUPLICATE");
            }
        }
    }

    private void validateStoredSteps(
            NotificationPolicyVersion version,
            List<NotificationPolicyStep> steps
    ) {
        if (steps == null || steps.isEmpty() || steps.size() > MAX_STEPS) {
            throw invalid("STEP_INVALID");
        }
        List<NotificationPolicyRequest.Step> requestSteps = steps.stream()
                .map(step -> new NotificationPolicyRequest.Step(
                        step.stepOrder(), step.delaySeconds(), step.channelId(),
                        step.recipientGroupId(), step.templateId(),
                        step.recoveryTemplateId(), step.ticketAction(), step.ticketPriority()))
                .toList();
        validateRequestedSteps(requestSteps, version.notifyOnRecovery());
    }

    private void validateDependencies(
            NotificationPolicyVersion version,
            List<NotificationPolicyStep> steps
    ) {
        List<NotificationPolicyDependencyStatus> statuses =
                mapper.findDependencyStatuses(steps);
        Map<UUID, NotificationPolicyDependencyStatus> byStep = new LinkedHashMap<>();
        statuses.forEach(status -> byStep.put(status.stepId(), status));
        for (NotificationPolicyStep step : steps) {
            NotificationPolicyDependencyStatus status = byStep.get(step.id());
            boolean invalid = status == null
                    || !"ENABLED".equals(status.channelStatus())
                    || !"ENABLED".equals(status.recipientGroupStatus())
                    || status.enabledRecipientCount() < 1
                    || !"ENABLED".equals(status.templateStatus())
                    || status.channelType() == null
                    || status.channelType() != status.templateChannelType()
                    || status.templateEventType() != NotificationEventType.ALARM_RAISED;
            if (version.notifyOnRecovery()) {
                invalid = invalid || !"ENABLED".equals(status.recoveryTemplateStatus())
                        || status.channelType() != status.recoveryTemplateChannelType()
                        || status.recoveryTemplateEventType()
                        != NotificationEventType.ALARM_RECOVERED;
            }
            if (invalid) {
                throw invalid("DEPENDENCY_INVALID");
            }
        }
    }

    private void validateOverlap(
            NotificationPolicy policy,
            NotificationPolicyVersion version
    ) {
        PolicyConditions currentConditions = readConditions(version.matchConditionsJson());
        Set<String> currentSeverities = new HashSet<>(
                readJson(version.severityFilterJson(), new TypeReference<List<String>>() { }));
        for (NotificationPolicyOverlapCandidate candidate
                : mapper.findOverlapCandidates(policy.priority(), policy.id())) {
            Set<String> candidateSeverities = new HashSet<>(
                    readJson(candidate.severityFilterJson(),
                            new TypeReference<List<String>>() { }));
            candidateSeverities.retainAll(currentSeverities);
            PolicyConditions candidateConditions = readConditions(
                    candidate.matchConditionsJson());
            if (!candidateSeverities.isEmpty()
                    && scopesOverlap(currentConditions.mineIds(), candidateConditions.mineIds())
                    && stringScopesOverlap(
                            currentConditions.ruleKeys(), candidateConditions.ruleKeys())) {
                throw new MineException(POLICY_OVERLAP, Map.of(
                        "conflictingPolicyId", candidate.policyId(),
                        "conflictingPolicyCode", candidate.policyCode()));
            }
        }
    }

    private boolean scopesOverlap(Set<UUID> first, Set<UUID> second) {
        if (first.isEmpty() || second.isEmpty()) {
            return true;
        }
        return first.stream().anyMatch(second::contains);
    }

    private boolean stringScopesOverlap(Set<String> first, Set<String> second) {
        if (first.isEmpty() || second.isEmpty()) {
            return true;
        }
        return first.stream().anyMatch(second::contains);
    }

    private List<NotificationPolicyStep> createSteps(
            UUID versionId,
            List<NotificationPolicyRequest.Step> requests,
            Instant createdAt
    ) {
        return requests.stream().map(step -> new NotificationPolicyStep(
                idGenerator.next(), versionId, step.stepOrder(), step.delaySeconds(),
                step.channelId(), step.recipientGroupId(), step.templateId(),
                step.recoveryTemplateId(), step.ticketAction(), step.ticketPriority(),
                createdAt)).toList();
    }

    private NotificationPolicyResponse loadResponse(NotificationPolicy policy) {
        NotificationPolicyVersion draft = mapper.findDraft(policy.id()).orElse(null);
        NotificationPolicyVersion published = mapper.findPublished(policy.id()).orElse(null);
        return toResponse(
                policy,
                draft,
                draft == null ? List.of() : mapper.findSteps(draft.id()),
                published,
                published == null ? List.of() : mapper.findSteps(published.id()));
    }

    private NotificationPolicyResponse toResponse(
            NotificationPolicy policy,
            NotificationPolicyVersion draft,
            List<NotificationPolicyStep> draftSteps,
            NotificationPolicyVersion published,
            List<NotificationPolicyStep> publishedSteps
    ) {
        return new NotificationPolicyResponse(
                policy.id(), policy.policyCode(), policy.name(), policy.description(),
                policy.priority(), policy.status(), toVersion(draft, draftSteps),
                toVersion(published, publishedSteps), policy.createdAt(),
                policy.updatedAt(), policy.archivedAt(), policy.version());
    }

    private NotificationPolicyResponse.Version toVersion(
            NotificationPolicyVersion version,
            List<NotificationPolicyStep> steps
    ) {
        if (version == null) {
            return null;
        }
        PolicyConditions conditions = readConditions(version.matchConditionsJson());
        List<String> severities = readJson(
                version.severityFilterJson(), new TypeReference<List<String>>() { });
        return new NotificationPolicyResponse.Version(
                version.id(), version.versionNumber(), version.state(),
                new NotificationPolicyResponse.MatchConditions(
                        conditions.mineIds(), conditions.ruleKeys()),
                List.copyOf(severities), version.stopOnAck(), version.notifyOnRecovery(),
                version.publicationReason(), version.publishedAt(),
                steps.stream().map(this::toStep).toList());
    }

    private NotificationPolicyResponse.Step toStep(NotificationPolicyStep step) {
        return new NotificationPolicyResponse.Step(
                step.id(), step.stepOrder(), step.delaySeconds(), step.channelId(),
                step.recipientGroupId(), step.templateId(), step.recoveryTemplateId(),
                step.ticketAction(), step.ticketPriority());
    }

    private NotificationPolicy requirePolicy(UUID policyId) {
        return mapper.findById(policyId).orElseThrow(() -> notFound(policyId));
    }

    private NotificationPolicy requireActiveLocked(UUID policyId, long expectedVersion) {
        NotificationPolicy policy = mapper.lockById(policyId)
                .orElseThrow(() -> notFound(policyId));
        if (!"ACTIVE".equals(policy.status())) {
            throw invalid("POLICY_ARCHIVED");
        }
        if (policy.version() != expectedVersion) {
            throw conflict(policyId);
        }
        return policy;
    }

    private PolicyConditions normalizeConditions(
            NotificationPolicyRequest.MatchConditions conditions
    ) {
        return new PolicyConditions(
                orderedIds(conditions.mineIds()), orderedKeys(conditions.ruleKeys()));
    }

    private Set<UUID> orderedIds(Set<UUID> ids) {
        LinkedHashSet<UUID> ordered = new LinkedHashSet<>();
        ids.stream().sorted(Comparator.comparing(UUID::toString)).forEach(ordered::add);
        return Set.copyOf(ordered);
    }

    private Set<String> orderedKeys(Set<String> keys) {
        return Set.copyOf(keys.stream().sorted().toList());
    }

    private String writeConditions(PolicyConditions conditions) {
        Map<String, List<String>> payload = new LinkedHashMap<>();
        payload.put("mineIds", conditions.mineIds().stream()
                .map(UUID::toString).sorted().toList());
        payload.put("ruleKeys", conditions.ruleKeys().stream().sorted().toList());
        return writeJson(payload);
    }

    private PolicyConditions readConditions(String json) {
        Map<String, Object> value = readJson(
                json, new TypeReference<Map<String, Object>>() { });
        List<UUID> mineIds = objectMapper.convertValue(
                value.getOrDefault("mineIds", List.of()), new TypeReference<List<UUID>>() { });
        List<String> ruleKeys = objectMapper.convertValue(
                value.getOrDefault("ruleKeys", List.of()),
                new TypeReference<List<String>>() { });
        return new PolicyConditions(
                Set.copyOf(mineIds), Set.copyOf(ruleKeys));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("通知升级策略 JSON 序列化失败", exception);
        }
    }

    private <T> T readJson(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException | NullPointerException exception) {
            throw new IllegalStateException("通知升级策略 JSON 结构无效", exception);
        }
    }

    private Map<String, Object> summary(
            NotificationPolicy policy,
            NotificationPolicyVersion version,
            List<NotificationPolicyStep> steps
    ) {
        return Map.of(
                "policyCode", policy.policyCode(),
                "priority", policy.priority(),
                "status", policy.status(),
                "aggregateVersion", policy.version(),
                "policyVersion", version.versionNumber(),
                "versionState", version.state(),
                "stepCount", steps.size(),
                "notifyOnRecovery", version.notifyOnRecovery());
    }

    private String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private MineException notFound(UUID policyId) {
        return new MineException(POLICY_NOT_FOUND, Map.of("policyId", policyId));
    }

    private MineException conflict(UUID policyId) {
        return new MineException(POLICY_CONFLICT, Map.of("policyId", policyId));
    }

    private MineException duplicate(String policyCode) {
        return new MineException(POLICY_DUPLICATE, Map.of("policyCode", policyCode));
    }

    private MineException invalid(String reason) {
        return new MineException(POLICY_INVALID, Map.of("reason", reason));
    }

    private UUID currentOperatorId() {
        try {
            return UUID.fromString(StpUtil.getLoginIdAsString());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /** 经过业务校验和稳定排序的策略输入。 */
    private record NormalizedPolicy(
            String policyCode,
            String name,
            String description,
            int priority,
            PolicyConditions matchConditions,
            List<String> severityFilter,
            boolean stopOnAck,
            boolean notifyOnRecovery,
            List<NotificationPolicyRequest.Step> steps
    ) {
    }

    /** 严格限定为矿区和告警规则集合的匹配条件。 */
    private record PolicyConditions(Set<UUID> mineIds, Set<String> ruleKeys) {
    }
}
