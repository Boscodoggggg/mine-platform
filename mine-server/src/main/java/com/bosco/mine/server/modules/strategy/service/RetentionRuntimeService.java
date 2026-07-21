package com.bosco.mine.server.modules.strategy.service;

import cn.dev33.satoken.stp.StpUtil;
import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.model.strategy.EffectiveStrategyResolver;
import com.bosco.mine.model.strategy.RetentionStrategyDefinition;
import com.bosco.mine.model.strategy.ScopedStrategyFragment;
import com.bosco.mine.model.strategy.StrategyScopeType;
import com.bosco.mine.model.strategy.StrategyValidationException;
import com.bosco.mine.server.modules.strategy.repository.RetentionPreviewEvidenceMapper;
import com.bosco.mine.server.modules.strategy.repository.RetentionRuntimeRepository;
import com.bosco.mine.server.modules.strategy.repository.StrategyConfigurationMapper;
import com.bosco.mine.server.modules.strategy.repository.StrategyScopeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 使用已保存策略版本计算逐设备最终保留策略，并把影响预览绑定到一次激活。
 */
@Service
public class RetentionRuntimeService implements RetentionActivationGuard, RetentionGroupChangeGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger(RetentionRuntimeService.class);
    private static final ErrorCode POLICY_INVALID =
            new ErrorCode("RETENTION-422-001", "数据保留策略未通过白名单校验");
    private static final ErrorCode SCOPE_INVALID =
            new ErrorCode("RETENTION-422-002", "数据保留预览作用范围不合法");
    private static final ErrorCode QUERY_INVALID =
            new ErrorCode("RETENTION-422-003", "数据保留运行查询参数不合法");
    private static final ErrorCode PREVIEW_REQUIRED =
            new ErrorCode("RETENTION-422-004", "数据保留激活必须提供精确影响预览凭证");
    private static final ErrorCode PREVIEW_STALE =
            new ErrorCode("RETENTION-409-001", "数据保留预览已过期、已使用或目标状态已变化");
    private static final ErrorCode GROUP_CHANGE_INVALID =
            new ErrorCode("RETENTION-422-005", "设备组保留影响预览参数不合法");
    private static final ErrorCode RUNTIME_UNAVAILABLE =
            new ErrorCode("RETENTION-503-001", "数据保留运行查询暂时不可用");
    private static final long PREVIEW_COUNT_LIMIT = 1_000_000L;
    private static final int RESOLUTION_BATCH_SIZE = 1_000;
    private static final Duration PREVIEW_TTL = Duration.ofMinutes(15);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final RetentionRuntimeRepository repository;
    private final StrategyConfigurationMapper strategyMapper;
    private final StrategyScopeMapper scopeMapper;
    private final RetentionPreviewEvidenceMapper evidenceMapper;
    private final ObjectMapper objectMapper;
    private final RetentionStrategyDefinition definition = new RetentionStrategyDefinition();
    private final EffectiveStrategyResolver resolver = new EffectiveStrategyResolver();
    private final IdGenerator idGenerator = IdGenerator.timeOrdered();

    /**
     * 创建数据保留预览和激活守卫。
     *
     * @param repository     TimescaleDB 只读运行仓储
     * @param strategyMapper 策略与设备查询接口
     * @param scopeMapper    产品和设备组查询接口
     * @param evidenceMapper 预览凭证数据接口
     * @param objectMapper   JSON 序列化器
     */
    public RetentionRuntimeService(
            RetentionRuntimeRepository repository,
            StrategyConfigurationMapper strategyMapper,
            StrategyScopeMapper scopeMapper,
            RetentionPreviewEvidenceMapper evidenceMapper,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.strategyMapper = strategyMapper;
        this.scopeMapper = scopeMapper;
        this.evidenceMapper = evidenceMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 使用已保存策略版本预览目标作用域发布后的逐设备最终删除影响。
     *
     * @param command 预览命令
     * @return 带短期一次性凭证的时点估算
     */
    @Transactional
    public RetentionPreviewView preview(PreviewCommand command) {
        validateCommand(command);
        StrategyConfigurationMapper.StrategyVersionRecord version = strategyMapper
                .findVersionById(command.strategyVersionId())
                .filter(record -> command.productId().equals(record.productId())
                        && RetentionStrategyDefinition.TYPE.equals(record.strategyType()))
                .orElseThrow(() -> new MineException(POLICY_INVALID, Map.of(
                        "strategyVersionId", command.strategyVersionId())));
        ValidatedCandidate candidate = validateValues(readMap(version.valuesJson()));
        Target target = resolveTarget(command);
        UUID previewToken = idGenerator.next();
        PreviewPlan plan = plan(
                previewToken, command.productId(), target, candidate.values(), target.deviceIds());
        RetentionRuntimeRepository.PreviewSnapshot snapshot;
        if (plan.enabledDeviceCount() == 0) {
            RetentionRuntimeRepository.DatasetImpact empty =
                    new RetentionRuntimeRepository.DatasetImpact(0, null, false);
            snapshot = new RetentionRuntimeRepository.PreviewSnapshot(Instant.now(), empty, empty);
        } else {
            try {
                snapshot = repository.preview(plan.finalPolicies(), PREVIEW_COUNT_LIMIT);
            } catch (RuntimeException exception) {
                throw unavailable("preview", exception);
            }
        }
        Instant expiresAt = snapshot.observedAt().plus(PREVIEW_TTL);
        evidenceMapper.insert(new RetentionPreviewEvidenceMapper.PreviewEvidence(
                previewToken, currentOperatorId(), "STRATEGY_RELEASE", null, null,
                version.id(), version.productId(),
                target.scopeType().name(), target.scopeId(), target.groupPriority(),
                candidateHash(previewToken, version.productId(), candidate.values()),
                plan.resolutionHash(), snapshot.observedAt(), expiresAt, null, null
        ));
        return new RetentionPreviewView(
                previewToken, snapshot.observedAt(), expiresAt, plan.finalPolicies().size(),
                plan.affectedDeviceCount(), plan.enabledDeviceCount(), candidate.policy(),
                toView(snapshot.rawTelemetry()), toView(snapshot.qualityEvidence())
        );
    }

    /**
     * 预览成员、优先级或归档变化间接激活当前设备组保留策略后的删除影响。
     *
     * @param command 设备组拟变更命令
     * @return 是否需要凭证以及对应影响预览
     */
    @Transactional
    public GroupChangePreviewView previewGroupChange(GroupChangePreviewCommand command) {
        GroupChangeCandidate candidate = resolveGroupChange(command);
        StrategyConfigurationMapper.StrategyReleaseRecord release = currentRetentionRelease(
                candidate.groupId());
        if (release == null || candidate.affectedDeviceIds().isEmpty()) {
            return new GroupChangePreviewView(false, null);
        }
        ValidatedCandidate groupPolicy = validateValues(readMap(release.valuesJson()));
        UUID previewToken = idGenerator.next();
        GroupChangePlan plan = planGroupChange(candidate, release, groupPolicy.values());
        RetentionRuntimeRepository.PreviewSnapshot snapshot = previewSnapshot(plan.finalPolicies());
        Instant expiresAt = snapshot.observedAt().plus(PREVIEW_TTL);
        evidenceMapper.insert(new RetentionPreviewEvidenceMapper.PreviewEvidence(
                previewToken, currentOperatorId(), "GROUP_CHANGE", candidate.changeType().name(),
                candidate.groupVersion(), release.strategyVersionId(), candidate.productId(),
                StrategyScopeType.GROUP.name(), candidate.groupId(), candidate.nextPriority(),
                groupChangeCandidateHash(candidate, release), plan.resolutionHash(),
                snapshot.observedAt(), expiresAt, null, null
        ));
        RetentionPreviewView preview = new RetentionPreviewView(
                previewToken, snapshot.observedAt(), expiresAt,
                candidate.affectedDeviceIds().size(), plan.affectedDeviceCount(),
                plan.enabledDeviceCount(), groupPolicy.policy(),
                toView(snapshot.rawTelemetry()), toView(snapshot.qualityEvidence())
        );
        return new GroupChangePreviewView(true, preview);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void verify(RetentionActivationGuard.ActivationCommand command) {
        if (command == null || command.previewToken() == null) {
            throw new MineException(PREVIEW_REQUIRED, Map.of("field", "retentionPreviewToken"));
        }
        ValidatedCandidate candidate = validateValues(command.values());
        RetentionPreviewEvidenceMapper.PreviewEvidence evidence = evidenceMapper
                .findForUpdate(command.previewToken())
                .orElseThrow(() -> stale("notFound"));
        boolean identityMatches = Objects.equals(evidence.operatorId(), command.operatorId())
                && "STRATEGY_RELEASE".equals(evidence.activationType())
                && evidence.strategyVersionId().equals(command.strategyVersionId())
                && evidence.productId().equals(command.productId())
                && evidence.scopeType().equals(command.scopeType().name())
                && evidence.scopeId().equals(command.scopeId())
                && Objects.equals(evidence.groupPriority(), command.groupPriority())
                && evidence.activatedAt() == null
                && evidence.expiresAt().isAfter(Instant.now());
        if (!identityMatches) {
            throw stale("identity");
        }
        Target target = new Target(
                command.scopeType(), command.scopeId(), command.groupPriority(),
                command.affectedDeviceIds());
        PreviewPlan current = plan(
                command.previewToken(), command.productId(), target,
                candidate.values(), command.affectedDeviceIds());
        String candidateHash = candidateHash(
                command.previewToken(), command.productId(), candidate.values());
        if (!evidence.candidateHash().equals(candidateHash)
                || !evidence.resolutionHash().equals(current.resolutionHash())) {
            throw stale("stateChanged");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void markActivated(UUID previewToken, UUID releaseId) {
        if (previewToken == null || releaseId == null
                || evidenceMapper.markActivated(previewToken, releaseId) != 1) {
            throw stale("activation");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean verify(RetentionGroupChangeGuard.ActivationCommand command) {
        validateGroupActivation(command);
        StrategyConfigurationMapper.StrategyReleaseRecord release = currentRetentionRelease(
                command.groupId());
        if (release == null || command.affectedDeviceIds().isEmpty()) {
            return false;
        }
        StpUtil.checkPermission("device:strategy:publish");
        if (command.previewToken() == null) {
            throw new MineException(PREVIEW_REQUIRED, Map.of("field", "retentionPreviewToken"));
        }
        RetentionPreviewEvidenceMapper.PreviewEvidence evidence = evidenceMapper
                .findForUpdate(command.previewToken())
                .orElseThrow(() -> stale("notFound"));
        boolean identityMatches = Objects.equals(evidence.operatorId(), command.operatorId())
                && "GROUP_CHANGE".equals(evidence.activationType())
                && command.changeType().name().equals(evidence.changeType())
                && Objects.equals(evidence.groupVersion(), command.groupVersion())
                && evidence.strategyVersionId().equals(release.strategyVersionId())
                && evidence.productId().equals(command.productId())
                && StrategyScopeType.GROUP.name().equals(evidence.scopeType())
                && evidence.scopeId().equals(command.groupId())
                && Objects.equals(evidence.groupPriority(), command.nextPriority())
                && evidence.activatedAt() == null
                && evidence.expiresAt().isAfter(Instant.now());
        if (!identityMatches) {
            throw stale("identity");
        }
        GroupChangeCandidate candidate = new GroupChangeCandidate(
                command.changeType(), command.groupId(), command.productId(),
                command.groupVersion(), command.previousPriority(), command.nextPriority(),
                command.addedDeviceIds(), command.removedDeviceIds(), command.affectedDeviceIds());
        String candidateHash = groupChangeCandidateHash(candidate, release);
        ValidatedCandidate groupPolicy = validateValues(readMap(release.valuesJson()));
        GroupChangePlan plan = planGroupChange(candidate, release, groupPolicy.values());
        if (!evidence.candidateHash().equals(candidateHash)
                || !evidence.resolutionHash().equals(plan.resolutionHash())) {
            throw stale("stateChanged");
        }
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void markGroupChangeActivated(UUID previewToken, UUID groupId) {
        if (previewToken == null || groupId == null
                || evidenceMapper.markGroupChangeActivated(previewToken, groupId) != 1) {
            throw stale("activation");
        }
    }

    /**
     * 查询最近有界数量的数据保留执行事实。
     *
     * @param limit 最大返回数量
     * @return 最近执行事实
     */
    public List<ExecutionView> recentExecutions(int limit) {
        if (limit < 1 || limit > 100) {
            throw new MineException(QUERY_INVALID, Map.of("field", "limit"));
        }
        try {
            return repository.findExecutions(limit).stream().map(this::toView).toList();
        } catch (RuntimeException exception) {
            throw unavailable("executions", exception);
        }
    }

    private PreviewPlan plan(
            UUID candidateReleaseId,
            UUID productId,
            Target target,
            Map<String, Object> candidateValues,
            List<UUID> deviceIds
    ) {
        List<RetentionRuntimeRepository.DeviceRetentionPolicy> finalPolicies = new ArrayList<>();
        Map<UUID, PolicyTransition> transitions = new LinkedHashMap<>();
        for (int start = 0; start < deviceIds.size(); start += RESOLUTION_BATCH_SIZE) {
            List<UUID> batch = deviceIds.subList(
                    start, Math.min(start + RESOLUTION_BATCH_SIZE, deviceIds.size()));
            Map<UUID, List<ScopedStrategyFragment>> current = new LinkedHashMap<>();
            batch.forEach(deviceId -> current.put(deviceId, new ArrayList<>()));
            for (StrategyConfigurationMapper.StrategyFragmentRecord record
                    : strategyMapper.findCurrentFragmentsForDevices(
                    productId, RetentionStrategyDefinition.TYPE, batch)) {
                current.get(record.deviceId()).add(toFragment(record));
            }
            for (UUID deviceId : batch) {
                List<ScopedStrategyFragment> beforeFragments = current.get(deviceId);
                EffectiveStrategyResolver.Resolution before = resolver.resolve(beforeFragments);
                List<ScopedStrategyFragment> afterFragments = beforeFragments.stream()
                        .filter(fragment -> fragment.scopeType() != target.scopeType()
                                || !fragment.scopeId().equals(target.scopeId()))
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
                afterFragments.add(new ScopedStrategyFragment(
                        candidateReleaseId, target.scopeType(), target.scopeId(),
                        target.groupPriority(), candidateValues));
                EffectiveStrategyResolver.Resolution after = resolver.resolve(afterFragments);
                if (!before.valid() || !after.valid()) {
                    throw new MineException(POLICY_INVALID, Map.of("deviceId", deviceId));
                }
                PolicyView beforePolicy = policyFromResolution(before);
                PolicyView afterPolicy = policyFromResolution(after);
                transitions.put(deviceId, new PolicyTransition(beforePolicy, afterPolicy));
                finalPolicies.add(new RetentionRuntimeRepository.DeviceRetentionPolicy(
                        deviceId, afterPolicy.enabled(), afterPolicy.rawTelemetryDays(),
                        afterPolicy.qualityEvidenceDays()));
            }
        }
        long affectedDeviceCount = transitions.values().stream()
                .filter(transition -> !Objects.equals(transition.before(), transition.after()))
                .count();
        long enabledDeviceCount = finalPolicies.stream()
                .filter(RetentionRuntimeRepository.DeviceRetentionPolicy::enabled).count();
        return new PreviewPlan(
                List.copyOf(finalPolicies), affectedDeviceCount, enabledDeviceCount,
                resolutionHash(productId, target, candidateValues, transitions));
    }

    private GroupChangePlan planGroupChange(
            GroupChangeCandidate candidate,
            StrategyConfigurationMapper.StrategyReleaseRecord release,
            Map<String, Object> groupValues
    ) {
        List<RetentionRuntimeRepository.DeviceRetentionPolicy> finalPolicies = new ArrayList<>();
        Map<UUID, PolicyTransition> transitions = new LinkedHashMap<>();
        List<UUID> deviceIds = candidate.affectedDeviceIds();
        for (int start = 0; start < deviceIds.size(); start += RESOLUTION_BATCH_SIZE) {
            List<UUID> batch = deviceIds.subList(
                    start, Math.min(start + RESOLUTION_BATCH_SIZE, deviceIds.size()));
            Map<UUID, List<ScopedStrategyFragment>> current = new LinkedHashMap<>();
            batch.forEach(deviceId -> current.put(deviceId, new ArrayList<>()));
            for (StrategyConfigurationMapper.StrategyFragmentRecord record
                    : strategyMapper.findCurrentFragmentsForDevices(
                    candidate.productId(), RetentionStrategyDefinition.TYPE, batch)) {
                current.get(record.deviceId()).add(toFragment(record));
            }
            for (UUID deviceId : batch) {
                List<ScopedStrategyFragment> beforeFragments = current.get(deviceId);
                EffectiveStrategyResolver.Resolution before = resolver.resolve(beforeFragments);
                List<ScopedStrategyFragment> afterFragments = beforeFragments.stream()
                        .filter(fragment -> fragment.scopeType() != StrategyScopeType.GROUP
                                || !fragment.scopeId().equals(candidate.groupId()))
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
                boolean appliesAfter = candidate.changeType() == ChangeType.PRIORITY
                        || (candidate.changeType() == ChangeType.MEMBERSHIP
                        && candidate.addedDeviceIds().contains(deviceId));
                if (appliesAfter) {
                    afterFragments.add(new ScopedStrategyFragment(
                            release.id(), StrategyScopeType.GROUP, candidate.groupId(),
                            candidate.nextPriority(), groupValues));
                }
                EffectiveStrategyResolver.Resolution after = resolver.resolve(afterFragments);
                if (!before.valid() || !after.valid()) {
                    throw new MineException(POLICY_INVALID, Map.of("deviceId", deviceId));
                }
                PolicyView beforePolicy = policyFromResolution(before);
                PolicyView afterPolicy = policyFromResolution(after);
                transitions.put(deviceId, new PolicyTransition(beforePolicy, afterPolicy));
                if (afterPolicy != null) {
                    finalPolicies.add(new RetentionRuntimeRepository.DeviceRetentionPolicy(
                            deviceId, afterPolicy.enabled(), afterPolicy.rawTelemetryDays(),
                            afterPolicy.qualityEvidenceDays()));
                }
            }
        }
        long affectedDeviceCount = transitions.values().stream()
                .filter(transition -> !Objects.equals(transition.before(), transition.after()))
                .count();
        long enabledDeviceCount = finalPolicies.stream()
                .filter(RetentionRuntimeRepository.DeviceRetentionPolicy::enabled).count();
        String resolutionHash = groupResolutionHash(candidate, release, transitions);
        return new GroupChangePlan(
                List.copyOf(finalPolicies), affectedDeviceCount, enabledDeviceCount, resolutionHash);
    }

    private RetentionRuntimeRepository.PreviewSnapshot previewSnapshot(
            List<RetentionRuntimeRepository.DeviceRetentionPolicy> policies
    ) {
        boolean enabled = policies.stream()
                .anyMatch(RetentionRuntimeRepository.DeviceRetentionPolicy::enabled);
        if (!enabled) {
            RetentionRuntimeRepository.DatasetImpact empty =
                    new RetentionRuntimeRepository.DatasetImpact(0, null, false);
            return new RetentionRuntimeRepository.PreviewSnapshot(Instant.now(), empty, empty);
        }
        try {
            return repository.preview(policies, PREVIEW_COUNT_LIMIT);
        } catch (RuntimeException exception) {
            throw unavailable("groupChangePreview", exception);
        }
    }

    private GroupChangeCandidate resolveGroupChange(GroupChangePreviewCommand command) {
        if (command == null || command.groupId() == null || command.changeType() == null
                || command.groupVersion() < 0) {
            throw invalidGroupChange("command");
        }
        StrategyScopeMapper.DeviceGroupRecord group = scopeMapper.findGroupById(command.groupId())
                .filter(record -> "ACTIVE".equals(record.status()))
                .orElseThrow(() -> invalidGroupChange("groupId"));
        if (group.version() != command.groupVersion()) {
            throw invalidGroupChange("groupVersion");
        }
        List<UUID> current = scopeMapper.findActiveMemberIds(group.id());
        return switch (command.changeType()) {
            case MEMBERSHIP -> membershipCandidate(command, group, current);
            case PRIORITY -> {
                if (command.nextPriority() == null || command.nextPriority() < 0
                        || command.nextPriority() > 1000 || command.nextPriority() == group.priority()) {
                    throw invalidGroupChange("nextPriority");
                }
                yield new GroupChangeCandidate(
                        command.changeType(), group.id(), group.productId(), group.version(),
                        group.priority(), command.nextPriority(), List.of(), List.of(), current);
            }
            case GROUP_ARCHIVE -> new GroupChangeCandidate(
                    command.changeType(), group.id(), group.productId(), group.version(),
                    group.priority(), group.priority(), List.of(), List.copyOf(current), current);
        };
    }

    private GroupChangeCandidate membershipCandidate(
            GroupChangePreviewCommand command,
            StrategyScopeMapper.DeviceGroupRecord group,
            List<UUID> current
    ) {
        List<UUID> desired = command.deviceIds() == null ? List.of() : command.deviceIds();
        java.util.LinkedHashSet<UUID> unique = new java.util.LinkedHashSet<>(desired);
        if (desired.size() > 5_000 || unique.size() != desired.size()
                || (!desired.isEmpty()
                && scopeMapper.countEligibleDevices(group.productId(), desired) != desired.size())) {
            throw invalidGroupChange("deviceIds");
        }
        List<UUID> normalized = List.copyOf(unique);
        java.util.Set<UUID> currentSet = java.util.Set.copyOf(current);
        List<UUID> added = normalized.stream().filter(id -> !currentSet.contains(id)).toList();
        List<UUID> removed = current.stream().filter(id -> !unique.contains(id)).toList();
        List<UUID> affected = new ArrayList<>(added.size() + removed.size());
        affected.addAll(added);
        affected.addAll(removed);
        return new GroupChangeCandidate(
                command.changeType(), group.id(), group.productId(), group.version(),
                group.priority(), group.priority(), added, removed, affected);
    }

    private StrategyConfigurationMapper.StrategyReleaseRecord currentRetentionRelease(UUID groupId) {
        return strategyMapper.findCurrentGroupReleases(groupId).stream()
                .filter(release -> RetentionStrategyDefinition.TYPE.equals(release.strategyType()))
                .findFirst().orElse(null);
    }

    private void validateGroupActivation(RetentionGroupChangeGuard.ActivationCommand command) {
        if (command == null || command.changeType() == null || command.groupId() == null
                || command.productId() == null || command.groupVersion() < 0
                || command.previousPriority() < 0 || command.previousPriority() > 1000
                || command.nextPriority() < 0 || command.nextPriority() > 1000) {
            throw invalidGroupChange("activation");
        }
        List<UUID> expected = new ArrayList<>();
        if (command.changeType() == ChangeType.MEMBERSHIP) {
            expected.addAll(command.addedDeviceIds());
            expected.addAll(command.removedDeviceIds());
        } else if (!command.addedDeviceIds().isEmpty() || !command.removedDeviceIds().isEmpty()) {
            throw invalidGroupChange("affectedDeviceIds");
        }
        if (command.changeType() == ChangeType.MEMBERSHIP
                && !expected.equals(command.affectedDeviceIds())) {
            throw invalidGroupChange("affectedDeviceIds");
        }
    }

    private Target resolveTarget(PreviewCommand command) {
        return switch (command.scopeType()) {
            case PRODUCT -> {
                if (!command.productId().equals(command.scopeId())
                        || !scopeMapper.isActiveProduct(command.productId())) {
                    throw invalidScope(command.scopeId());
                }
                yield new Target(
                        StrategyScopeType.PRODUCT, command.scopeId(), null,
                        List.copyOf(strategyMapper.findUnarchivedDeviceIds(command.productId())));
            }
            case GROUP -> {
                StrategyScopeMapper.DeviceGroupRecord group = scopeMapper
                        .findGroupById(command.scopeId())
                        .filter(record -> command.productId().equals(record.productId())
                                && "ACTIVE".equals(record.status()))
                        .orElseThrow(() -> invalidScope(command.scopeId()));
                List<UUID> deviceIds = scopeMapper.findActiveMembers(group.id()).stream()
                        .filter(member -> !"ARCHIVED".equals(member.status()))
                        .map(StrategyScopeMapper.DeviceGroupMemberRecord::id).toList();
                yield new Target(StrategyScopeType.GROUP, group.id(), group.priority(), deviceIds);
            }
            case DEVICE -> {
                StrategyConfigurationMapper.DeviceContextRecord device = strategyMapper
                        .findDeviceContext(command.scopeId())
                        .filter(record -> command.productId().equals(record.productId())
                                && !"ARCHIVED".equals(record.status()))
                        .orElseThrow(() -> invalidScope(command.scopeId()));
                yield new Target(StrategyScopeType.DEVICE, device.id(), null, List.of(device.id()));
            }
        };
    }

    private void validateCommand(PreviewCommand command) {
        if (command == null || command.productId() == null || command.strategyVersionId() == null
                || command.scopeType() == null || command.scopeId() == null) {
            throw new MineException(SCOPE_INVALID, Map.of("field", "scope"));
        }
    }

    @SuppressWarnings("unchecked")
    private ValidatedCandidate validateValues(Map<String, ?> source) {
        try {
            Map<String, Object> values = definition.validate(source);
            Map<String, Object> normalized = (Map<String, Object>) values.get(
                    RetentionStrategyDefinition.POLICY);
            PolicyView policy = new PolicyView(
                    (Boolean) normalized.get(RetentionStrategyDefinition.ENABLED),
                    (String) normalized.get(RetentionStrategyDefinition.RETENTION_CLASS),
                    (Integer) normalized.get(RetentionStrategyDefinition.RAW_TELEMETRY_DAYS),
                    (Integer) normalized.get(RetentionStrategyDefinition.QUALITY_EVIDENCE_DAYS));
            return new ValidatedCandidate(Map.copyOf(values), policy);
        } catch (StrategyValidationException exception) {
            throw new MineException(POLICY_INVALID, Map.of("violations", exception.violations()));
        }
    }

    @SuppressWarnings("unchecked")
    private PolicyView policyFromResolution(EffectiveStrategyResolver.Resolution resolution) {
        Object policy = resolution.values().get(RetentionStrategyDefinition.POLICY);
        if (!(policy instanceof Map<?, ?> policyMap)) {
            return null;
        }
        return validateValues(Map.of(
                RetentionStrategyDefinition.POLICY, (Map<String, Object>) policyMap)).policy();
    }

    private ScopedStrategyFragment toFragment(
            StrategyConfigurationMapper.StrategyFragmentRecord record
    ) {
        return new ScopedStrategyFragment(
                record.releaseId(), StrategyScopeType.valueOf(record.scopeType()), record.scopeId(),
                record.groupPriority(), validateValues(readMap(record.valuesJson())).values());
    }

    private String candidateHash(UUID releaseId, UUID productId, Map<String, Object> values) {
        return resolver.resolve(List.of(new ScopedStrategyFragment(
                releaseId, StrategyScopeType.PRODUCT, productId, null, values))).contentHash();
    }

    private String resolutionHash(
            UUID productId,
            Target target,
            Map<String, Object> candidateValues,
            Map<UUID, PolicyTransition> transitions
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, productId + "|" + target.scopeType() + "|" + target.scopeId()
                    + "|" + Objects.toString(target.groupPriority(), "-") + "\n");
            update(digest, candidateHash(target.scopeId(), productId, candidateValues) + "\n");
            transitions.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString)))
                    .forEach(entry -> update(digest, entry.getKey() + "|"
                            + policyKey(entry.getValue().before()) + "|"
                            + policyKey(entry.getValue().after()) + "\n"));
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JRE 不支持 SHA-256", exception);
        }
    }

    private String groupChangeCandidateHash(
            GroupChangeCandidate candidate,
            StrategyConfigurationMapper.StrategyReleaseRecord release
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, candidate.productId() + "|" + candidate.groupId() + "|"
                    + candidate.groupVersion() + "|" + candidate.changeType() + "|"
                    + candidate.previousPriority() + "|" + candidate.nextPriority() + "|"
                    + release.id() + "|" + release.strategyVersionId() + "|"
                    + release.contentHash() + "\n");
            updateSortedIds(digest, "added", candidate.addedDeviceIds());
            updateSortedIds(digest, "removed", candidate.removedDeviceIds());
            updateSortedIds(digest, "affected", candidate.affectedDeviceIds());
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JRE 不支持 SHA-256", exception);
        }
    }

    private String groupResolutionHash(
            GroupChangeCandidate candidate,
            StrategyConfigurationMapper.StrategyReleaseRecord release,
            Map<UUID, PolicyTransition> transitions
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, groupChangeCandidateHash(candidate, release) + "\n");
            transitions.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString)))
                    .forEach(entry -> update(digest, entry.getKey() + "|"
                            + policyKey(entry.getValue().before()) + "|"
                            + policyKey(entry.getValue().after()) + "\n"));
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JRE 不支持 SHA-256", exception);
        }
    }

    private void updateSortedIds(MessageDigest digest, String name, List<UUID> ids) {
        ids.stream().sorted(Comparator.comparing(UUID::toString))
                .forEach(id -> update(digest, name + "|" + id + "\n"));
    }

    private String policyKey(PolicyView policy) {
        return policy == null ? "-" : policy.enabled() + ":" + policy.retentionClass() + ":"
                + policy.rawTelemetryDays() + ":" + policy.qualityEvidenceDays();
    }

    private void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JacksonException exception) {
            throw new IllegalStateException("数据库中的数据保留策略 JSON 无法解析", exception);
        }
    }

    private DatasetImpactView toView(RetentionRuntimeRepository.DatasetImpact impact) {
        return new DatasetImpactView(
                impact.eligibleRows(), impact.earliestEligibleAt(), impact.atLeast());
    }

    private ExecutionView toView(RetentionRuntimeRepository.ExecutionRecord record) {
        return new ExecutionView(
                record.executionId(), record.instanceId(), record.status(), record.policyCount(),
                record.rawRowsDeleted(), record.qualityRowsDeleted(), record.batchCount(),
                record.startedAt(), record.completedAt(), record.lastError());
    }

    private UUID currentOperatorId() {
        try {
            return UUID.fromString(StpUtil.getLoginIdAsString());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private MineException invalidScope(UUID scopeId) {
        return new MineException(SCOPE_INVALID, Map.of("scopeId", scopeId));
    }

    private MineException invalidGroupChange(String field) {
        return new MineException(GROUP_CHANGE_INVALID, Map.of("field", field));
    }

    private MineException stale(String reason) {
        return new MineException(PREVIEW_STALE, Map.of("reason", reason));
    }

    private MineException unavailable(String operation, RuntimeException exception) {
        LOGGER.warn("数据保留只读运行查询失败: operation={}, exceptionType={}",
                operation, exception.getClass().getSimpleName());
        return new MineException(RUNTIME_UNAVAILABLE, Map.of());
    }

    /**
     * 数据保留影响预览命令。
     *
     * @param productId        产品标识
     * @param strategyVersionId 已保存策略版本标识
     * @param scopeType        作用域类型
     * @param scopeId          作用域标识
     */
    public record PreviewCommand(
            UUID productId,
            UUID strategyVersionId,
            StrategyScopeType scopeType,
            UUID scopeId
    ) {
    }

    /**
     * 设备组拟变更影响预览命令。
     *
     * @param groupId      设备组标识
     * @param changeType   成员、优先级或归档变化
     * @param groupVersion 设备组乐观锁版本
     * @param deviceIds    成员变化后的完整设备集合
     * @param nextPriority 优先级变化后的值
     */
    public record GroupChangePreviewCommand(
            UUID groupId,
            ChangeType changeType,
            long groupVersion,
            List<UUID> deviceIds,
            Integer nextPriority
    ) {
        /**
         * 冻结目标成员集合。
         */
        public GroupChangePreviewCommand {
            deviceIds = deviceIds == null ? List.of() : List.copyOf(deviceIds);
        }
    }

    /**
     * 设备组变化预览响应。
     *
     * @param required 是否存在需要显式确认的当前设备组保留发布
     * @param preview  需要确认时的逐设备删除影响
     */
    public record GroupChangePreviewView(boolean required, RetentionPreviewView preview) {
    }

    /**
     * 已规范化的数据保留策略。
     *
     * @param enabled             是否启用执行
     * @param retentionClass      保留等级
     * @param rawTelemetryDays    原始遥测保留天数
     * @param qualityEvidenceDays 质量证据保留天数
     */
    public record PolicyView(
            boolean enabled,
            String retentionClass,
            int rawTelemetryDays,
            int qualityEvidenceDays
    ) {
    }

    /**
     * 数据保留影响预览响应。
     *
     * @param previewToken        一次性预览凭证
     * @param observedAt          影响统计时点
     * @param expiresAt           凭证过期时间
     * @param targetDeviceCount   目标设备数
     * @param affectedDeviceCount 最终策略值发生变化的设备数
     * @param enabledDeviceCount  发布后仍启用删除的设备数
     * @param policy              候选版本中的规范化策略
     * @param rawTelemetry        原始数值遥测影响
     * @param qualityEvidence     质量证据影响
     */
    public record RetentionPreviewView(
            UUID previewToken,
            Instant observedAt,
            Instant expiresAt,
            long targetDeviceCount,
            long affectedDeviceCount,
            long enabledDeviceCount,
            PolicyView policy,
            DatasetImpactView rawTelemetry,
            DatasetImpactView qualityEvidence
    ) {
    }

    /**
     * 单个数据集影响响应。
     *
     * @param eligibleRows       达到年龄条件的行数
     * @param earliestEligibleAt 最早达到条件的时间
     * @param atLeast            是否因为统计上限仍有更多行
     */
    public record DatasetImpactView(
            long eligibleRows,
            Instant earliestEligibleAt,
            boolean atLeast
    ) {
    }

    /**
     * 数据保留执行事实响应。
     *
     * @param executionId        执行标识
     * @param instanceId         Telemetry 实例标识
     * @param status             执行状态
     * @param policyCount        启用策略数量
     * @param rawRowsDeleted     原始遥测删除行数
     * @param qualityRowsDeleted 质量证据删除行数
     * @param batchCount         已提交批次数
     * @param startedAt          开始时间
     * @param completedAt        完成时间
     * @param lastError          失败时的脱敏摘要
     */
    public record ExecutionView(
            UUID executionId,
            String instanceId,
            String status,
            int policyCount,
            long rawRowsDeleted,
            long qualityRowsDeleted,
            int batchCount,
            Instant startedAt,
            Instant completedAt,
            String lastError
    ) {
    }

    private record Target(
            StrategyScopeType scopeType,
            UUID scopeId,
            Integer groupPriority,
            List<UUID> deviceIds
    ) {
        private Target {
            deviceIds = List.copyOf(deviceIds);
        }
    }

    private record ValidatedCandidate(Map<String, Object> values, PolicyView policy) {
    }

    private record PolicyTransition(PolicyView before, PolicyView after) {
    }

    private record PreviewPlan(
            List<RetentionRuntimeRepository.DeviceRetentionPolicy> finalPolicies,
            long affectedDeviceCount,
            long enabledDeviceCount,
            String resolutionHash
    ) {
    }

    private record GroupChangeCandidate(
            ChangeType changeType,
            UUID groupId,
            UUID productId,
            long groupVersion,
            int previousPriority,
            int nextPriority,
            List<UUID> addedDeviceIds,
            List<UUID> removedDeviceIds,
            List<UUID> affectedDeviceIds
    ) {
        private GroupChangeCandidate {
            addedDeviceIds = List.copyOf(addedDeviceIds);
            removedDeviceIds = List.copyOf(removedDeviceIds);
            affectedDeviceIds = List.copyOf(affectedDeviceIds);
        }
    }

    private record GroupChangePlan(
            List<RetentionRuntimeRepository.DeviceRetentionPolicy> finalPolicies,
            long affectedDeviceCount,
            long enabledDeviceCount,
            String resolutionHash
    ) {
    }
}
