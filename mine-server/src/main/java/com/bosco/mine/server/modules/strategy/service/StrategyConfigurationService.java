package com.bosco.mine.server.modules.strategy.service;

import cn.dev33.satoken.stp.StpUtil;
import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.model.strategy.EffectiveStrategyResolver;
import com.bosco.mine.model.strategy.OnlineStatusStrategyDefinition;
import com.bosco.mine.model.strategy.ScopedStrategyFragment;
import com.bosco.mine.model.strategy.StrategyDefinition;
import com.bosco.mine.model.strategy.StrategyScopeType;
import com.bosco.mine.model.strategy.StrategyValidationException;
import com.bosco.mine.server.modules.strategy.repository.StrategyConfigurationMapper;
import com.bosco.mine.server.modules.strategy.repository.StrategyScopeMapper;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 编排策略草稿、不可变发布、冲突预检、回滚和有效值查询。
 */
@Service
public class StrategyConfigurationService implements StrategyConflictGuard {

    private static final ErrorCode STRATEGY_CONFLICT =
            new ErrorCode("STRATEGY-409-001", "同优先级设备组策略存在字段冲突");
    private static final ErrorCode DRAFT_VERSION_CONFLICT =
            new ErrorCode("STRATEGY-409-005", "策略草稿已被其他管理员修改，请刷新后重试");
    private static final ErrorCode IDEMPOTENCY_CONFLICT =
            new ErrorCode("STRATEGY-409-006", "幂等键已用于其他策略发布请求");
    private static final ErrorCode VERSION_NOT_FOUND =
            new ErrorCode("STRATEGY-404-002", "策略版本不存在");
    private static final ErrorCode RELEASE_NOT_FOUND =
            new ErrorCode("STRATEGY-404-003", "策略发布不存在");
    private static final ErrorCode DEVICE_NOT_FOUND =
            new ErrorCode("STRATEGY-404-004", "设备不存在");
    private static final ErrorCode DEFINITION_INVALID =
            new ErrorCode("STRATEGY-422-004", "策略字段未通过类型注册表校验");
    private static final ErrorCode RELEASE_INVALID =
            new ErrorCode("STRATEGY-422-005", "策略发布参数或作用范围不合法");
    private static final int RESOLUTION_BATCH_SIZE = 1_000;
    private static final int MAX_CONFLICT_DETAILS = 100;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final StrategyConfigurationMapper mapper;
    private final StrategyScopeMapper scopeMapper;
    private final OperationAuditService auditService;
    private final ObjectMapper objectMapper;
    private final EffectiveStrategyResolver resolver = new EffectiveStrategyResolver();
    private final IdGenerator idGenerator = IdGenerator.timeOrdered();

    /**
     * 创建策略配置服务。
     *
     * @param mapper         策略版本与发布数据接口
     * @param scopeMapper    设备组与作用域数据接口
     * @param auditService   操作审计服务
     * @param objectMapper   JSON 序列化器
     */
    public StrategyConfigurationService(
            StrategyConfigurationMapper mapper,
            StrategyScopeMapper scopeMapper,
            OperationAuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.mapper = mapper;
        this.scopeMapper = scopeMapper;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建经过白名单类型校验的策略草稿。
     *
     * @param command 草稿创建命令
     * @return 新建策略版本
     */
    @Transactional
    public StrategyVersionView createDraft(CreateDraftCommand command) {
        if (command == null || command.productId() == null) {
            throw releaseInvalid("productId");
        }
        String strategyType = normalizeType(command.strategyType());
        Map<String, Object> values = validateValues(strategyType, command.values());
        mapper.lockProduct(command.productId());
        if (!scopeMapper.isActiveProduct(command.productId())) {
            throw releaseInvalid("productId");
        }
        long versionNumber = mapper.nextVersionNumber(command.productId(), strategyType);
        UUID versionId = idGenerator.next();
        String description = normalize(command.description());
        String valuesJson = writeJson(new TreeMap<>(values));
        UUID operatorId = currentOperatorId();
        mapper.insertVersion(
                versionId, command.productId(), strategyType, versionNumber,
                valuesJson, description, operatorId
        );
        Instant now = Instant.now();
        StrategyVersionView created = new StrategyVersionView(
                versionId, command.productId(), strategyType, versionNumber, "DRAFT",
                values, null, description, null, now, now, 0
        );
        auditService.recordSuccess(
                "DEVICE_STRATEGY", "CREATE_STRATEGY_DRAFT", "device:strategy:edit",
                "STRATEGY_VERSION", versionId, Map.of(), versionSummary(created)
        );
        return created;
    }

    /**
     * 使用乐观锁更新未发布策略草稿。
     *
     * @param versionId 策略版本标识
     * @param command   草稿修改命令
     * @return 修改后的策略版本
     */
    @Transactional
    public StrategyVersionView updateDraft(UUID versionId, UpdateDraftCommand command) {
        StrategyConfigurationMapper.StrategyVersionRecord before = requireVersion(versionId);
        if (!"DRAFT".equals(before.status())) {
            throw releaseInvalid("strategyVersionId");
        }
        Map<String, Object> values = validateValues(before.strategyType(), command.values());
        String description = normalize(command.description());
        String valuesJson = writeJson(new TreeMap<>(values));
        if (mapper.updateDraft(
                versionId, valuesJson, description, command.version(), currentOperatorId()
        ) == 0) {
            throw new MineException(DRAFT_VERSION_CONFLICT, Map.of("strategyVersionId", versionId));
        }
        StrategyVersionView updated = new StrategyVersionView(
                before.id(), before.productId(), before.strategyType(), before.versionNumber(),
                before.status(), values, before.contentHash(), description, before.publishedAt(),
                before.createdAt(), Instant.now(), command.version() + 1
        );
        auditService.recordSuccess(
                "DEVICE_STRATEGY", "UPDATE_STRATEGY_DRAFT", "device:strategy:edit",
                "STRATEGY_VERSION", versionId,
                versionSummary(toVersionView(before)), versionSummary(updated)
        );
        return updated;
    }

    /**
     * 查询产品下最近的策略版本。
     *
     * @param productId    产品标识
     * @param strategyType 策略类型
     * @param status       可选版本状态
     * @param limit        最大返回数量
     * @return 策略版本列表
     */
    @Transactional(readOnly = true)
    public List<StrategyVersionView> findVersions(
            UUID productId,
            String strategyType,
            String status,
            int limit
    ) {
        String normalizedType = normalizeType(strategyType);
        if (productId == null || limit < 1 || limit > 100
                || status != null && !List.of("DRAFT", "PUBLISHED").contains(status)) {
            throw releaseInvalid("query");
        }
        return mapper.findVersions(productId, normalizedType, status, limit).stream()
                .map(this::toVersionView)
                .toList();
    }

    /**
     * 按资源标识查询单个策略版本。
     *
     * @param versionId 策略版本标识
     * @return 策略版本详情
     */
    @Transactional(readOnly = true)
    public StrategyVersionView findVersion(UUID versionId) {
        return toVersionView(requireVersion(versionId));
    }

    /**
     * 发布策略草稿到产品、设备组或单设备作用范围。
     *
     * @param command 发布命令
     * @return 不可变策略发布
     */
    @Transactional
    public StrategyReleaseView publish(PublishCommand command) {
        validatePublishCommand(command);
        UUID operatorId = currentOperatorId();
        String idempotencyKey = command.idempotencyKey().strip();
        String reason = command.reason().strip();
        mapper.lockIdempotencyKey(operatorId, idempotencyKey);
        Optional<StrategyConfigurationMapper.StrategyReleaseRecord> existing =
                mapper.findReleaseByIdempotency(operatorId, idempotencyKey);
        if (existing.isPresent()) {
            verifySamePublishRequest(existing.orElseThrow(), command, reason);
            return toReleaseView(existing.get());
        }

        StrategyConfigurationMapper.StrategyVersionRecord initial = requireVersion(command.strategyVersionId());
        mapper.lockProduct(initial.productId());
        StrategyConfigurationMapper.StrategyVersionRecord version = mapper
                .findVersionForUpdate(command.strategyVersionId())
                .orElseThrow(() -> new MineException(
                        VERSION_NOT_FOUND, Map.of("strategyVersionId", command.strategyVersionId())));
        if (!"DRAFT".equals(version.status()) || !initial.productId().equals(version.productId())) {
            throw releaseInvalid("strategyVersionId");
        }
        Map<String, Object> values = validateValues(version.strategyType(), readMap(version.valuesJson()));
        Target target = lockTarget(version.productId(), command.scopeType(), command.scopeId());
        UUID releaseId = idGenerator.next();
        assertNoConflicts(
                version.productId(), version.strategyType(), target, values, releaseId,
                affectedDeviceIds(version.productId(), target)
        );
        String contentHash = contentHash(version.productId(), values, releaseId);
        if (mapper.publishVersion(
                version.id(), contentHash, version.version(), operatorId
        ) == 0) {
            throw new MineException(DRAFT_VERSION_CONFLICT, Map.of("strategyVersionId", version.id()));
        }
        return createRelease(
                version.productId(), version.id(), version.strategyType(), target,
                "PUBLISH", null, reason, idempotencyKey,
                contentHash, operatorId, releaseId
        );
    }

    /**
     * 复用历史不可变策略版本创建更高序号的回滚发布。
     *
     * @param historicalReleaseId 历史发布标识
     * @param reason              回滚原因
     * @param idempotencyKey      幂等键
     * @return 新回滚发布
     */
    @Transactional
    public StrategyReleaseView rollback(
            UUID historicalReleaseId,
            String reason,
            String idempotencyKey
    ) {
        requireText(reason, "reason", 500);
        requireText(idempotencyKey, "idempotencyKey", 128);
        UUID operatorId = currentOperatorId();
        String normalizedIdempotencyKey = idempotencyKey.strip();
        String normalizedReason = reason.strip();
        mapper.lockIdempotencyKey(operatorId, normalizedIdempotencyKey);
        Optional<StrategyConfigurationMapper.StrategyReleaseRecord> existing =
                mapper.findReleaseByIdempotency(operatorId, normalizedIdempotencyKey);
        if (existing.isPresent()) {
            verifySameRollbackRequest(existing.orElseThrow(), historicalReleaseId, normalizedReason);
            return toReleaseView(existing.get());
        }
        StrategyConfigurationMapper.StrategyReleaseRecord historical = mapper.findRelease(historicalReleaseId)
                .orElseThrow(() -> new MineException(
                        RELEASE_NOT_FOUND, Map.of("releaseId", historicalReleaseId)));
        mapper.lockProduct(historical.productId());
        Map<String, Object> values = validateValues(
                historical.strategyType(), readMap(historical.valuesJson()));
        Target target = lockTarget(
                historical.productId(), StrategyScopeType.valueOf(historical.scopeType()), historical.scopeId());
        UUID releaseId = idGenerator.next();
        assertNoConflicts(
                historical.productId(), historical.strategyType(), target, values, releaseId,
                affectedDeviceIds(historical.productId(), target)
        );
        return createRelease(
                historical.productId(), historical.strategyVersionId(), historical.strategyType(), target,
                "ROLLBACK", historicalReleaseId, normalizedReason, normalizedIdempotencyKey,
                historical.contentHash(), operatorId, releaseId
        );
    }

    /**
     * 查询产品和策略类型的最近发布历史。
     *
     * @param productId    产品标识
     * @param strategyType 策略类型
     * @param limit        最大返回数量
     * @return 发布历史
     */
    @Transactional(readOnly = true)
    public List<StrategyReleaseView> findReleases(
            UUID productId,
            String strategyType,
            int limit
    ) {
        if (productId == null || limit < 1 || limit > 100) {
            throw releaseInvalid("query");
        }
        return mapper.findReleases(productId, normalizeType(strategyType), limit).stream()
                .map(this::toReleaseView)
                .toList();
    }

    /**
     * 按资源标识查询单个不可变策略发布。
     *
     * @param releaseId 策略发布标识
     * @return 策略发布详情
     */
    @Transactional(readOnly = true)
    public StrategyReleaseView findRelease(UUID releaseId) {
        if (releaseId == null) {
            throw releaseInvalid("releaseId");
        }
        return mapper.findRelease(releaseId)
                .map(this::toReleaseView)
                .orElseThrow(() -> new MineException(
                        RELEASE_NOT_FOUND, Map.of("releaseId", releaseId)));
    }

    /**
     * 查询单台设备当前有效策略、逐字段来源和稳定内容哈希。
     *
     * @param deviceId     设备标识
     * @param strategyType 策略类型
     * @return 有效策略解析结果
     */
    @Transactional(readOnly = true)
    public EffectiveStrategyView findEffective(UUID deviceId, String strategyType) {
        String normalizedType = normalizeType(strategyType);
        StrategyConfigurationMapper.DeviceContextRecord device = mapper.findDeviceContext(deviceId)
                .filter(record -> !"ARCHIVED".equals(record.status()))
                .orElseThrow(() -> new MineException(DEVICE_NOT_FOUND, Map.of("deviceId", deviceId)));
        List<StrategyConfigurationMapper.StrategyFragmentRecord> records =
                mapper.findCurrentFragmentsForDevices(
                        device.productId(), normalizedType, List.of(deviceId));
        EffectiveStrategyResolver.Resolution resolution = resolver.resolve(
                records.stream().map(this::toFragment).toList());
        return new EffectiveStrategyView(
                deviceId, device.productId(), normalizedType, resolution.valid(),
                resolution.values(), resolution.sources(), resolution.conflicts(), resolution.contentHash()
        );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void verifyMembershipChange(
            StrategyScopeMapper.DeviceGroupRecord group,
            List<UUID> addedDeviceIds
    ) {
        verifyGroupScopeMutation(group, group.priority(), addedDeviceIds);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void verifyPriorityChange(
            StrategyScopeMapper.DeviceGroupRecord group,
            int newPriority,
            List<UUID> currentDeviceIds
    ) {
        if (group.priority() != newPriority) {
            verifyGroupScopeMutation(group, newPriority, currentDeviceIds);
        }
    }

    private void verifyGroupScopeMutation(
            StrategyScopeMapper.DeviceGroupRecord group,
            int priority,
            List<UUID> affectedDeviceIds
    ) {
        if (affectedDeviceIds.isEmpty()) {
            return;
        }
        Target candidateTarget = new Target(StrategyScopeType.GROUP, group.id(), priority);
        for (StrategyConfigurationMapper.StrategyReleaseRecord release
                : mapper.findCurrentGroupReleases(group.id())) {
            Map<String, Object> values = validateValues(
                    release.strategyType(), readMap(release.valuesJson()));
            assertNoConflicts(
                    group.productId(), release.strategyType(), candidateTarget, values,
                    release.id(), affectedDeviceIds
            );
        }
    }

    private StrategyReleaseView createRelease(
            UUID productId,
            UUID strategyVersionId,
            String strategyType,
            Target target,
            String action,
            UUID rollbackOfReleaseId,
            String reason,
            String idempotencyKey,
            String contentHash,
            UUID operatorId,
            UUID releaseId
    ) {
        long releaseSequence = mapper.nextReleaseSequence(productId);
        mapper.insertRelease(
                releaseId, productId, strategyVersionId, strategyType, releaseSequence,
                target.scopeType().name(), target.scopeId(), target.groupPriority(), action,
                rollbackOfReleaseId, reason.strip(), idempotencyKey.strip(), operatorId
        );
        StrategyReleaseView release = new StrategyReleaseView(
                releaseId, productId, strategyVersionId, strategyType, releaseSequence,
                target.scopeType(), target.scopeId(), target.groupPriority(), action,
                rollbackOfReleaseId, contentHash
        );
        auditService.recordSuccess(
                "DEVICE_STRATEGY", action + "_STRATEGY", "device:strategy:publish",
                "STRATEGY_RELEASE", releaseId, Map.of(), releaseSummary(release)
        );
        return release;
    }

    private void assertNoConflicts(
            UUID productId,
            String strategyType,
            Target target,
            Map<String, Object> candidateValues,
            UUID candidateReleaseId,
            List<UUID> affectedDeviceIds
    ) {
        List<ConflictViolation> violations = new ArrayList<>();
        long conflictCount = 0;
        for (int start = 0; start < affectedDeviceIds.size(); start += RESOLUTION_BATCH_SIZE) {
            List<UUID> batch = affectedDeviceIds.subList(
                    start, Math.min(start + RESOLUTION_BATCH_SIZE, affectedDeviceIds.size()));
            Map<UUID, List<ScopedStrategyFragment>> byDevice = new LinkedHashMap<>();
            batch.forEach(deviceId -> byDevice.put(deviceId, new ArrayList<>()));
            for (StrategyConfigurationMapper.StrategyFragmentRecord record
                    : mapper.findCurrentFragmentsForDevices(productId, strategyType, batch)) {
                StrategyScopeType scopeType = StrategyScopeType.valueOf(record.scopeType());
                if (scopeType != target.scopeType() || !record.scopeId().equals(target.scopeId())) {
                    byDevice.get(record.deviceId()).add(toFragment(record));
                }
            }
            for (UUID deviceId : batch) {
                List<ScopedStrategyFragment> fragments = byDevice.get(deviceId);
                fragments.add(new ScopedStrategyFragment(
                        candidateReleaseId,
                        target.scopeType(),
                        target.scopeId(),
                        target.groupPriority(),
                        candidateValues
                ));
                EffectiveStrategyResolver.Resolution resolution = resolver.resolve(fragments);
                for (EffectiveStrategyResolver.Conflict conflict : resolution.conflicts()) {
                    conflictCount++;
                    if (violations.size() < MAX_CONFLICT_DETAILS) {
                        violations.add(new ConflictViolation(
                                deviceId, conflict.field(), conflict.groupPriority(), conflict.groupIds()
                        ));
                    }
                }
            }
        }
        if (conflictCount > 0) {
            throw new MineException(STRATEGY_CONFLICT, Map.of(
                    "conflictCount", conflictCount,
                    "violations", List.copyOf(violations),
                    "truncated", conflictCount > violations.size()
            ));
        }
    }

    private Target lockTarget(UUID productId, StrategyScopeType scopeType, UUID scopeId) {
        if (scopeType == null || scopeId == null) {
            throw releaseInvalid("scope");
        }
        return switch (scopeType) {
            case PRODUCT -> {
                if (!productId.equals(scopeId) || !scopeMapper.isActiveProduct(productId)) {
                    throw releaseInvalid("scopeId");
                }
                yield new Target(scopeType, scopeId, null);
            }
            case GROUP -> {
                StrategyScopeMapper.DeviceGroupRecord group = scopeMapper.findGroupByIdForUpdate(scopeId)
                        .filter(record -> productId.equals(record.productId()) && "ACTIVE".equals(record.status()))
                        .orElseThrow(() -> releaseInvalid("scopeId"));
                yield new Target(scopeType, scopeId, group.priority());
            }
            case DEVICE -> {
                StrategyConfigurationMapper.DeviceContextRecord device = mapper
                        .findDeviceContextForUpdate(scopeId)
                        .filter(record -> productId.equals(record.productId())
                                && !"ARCHIVED".equals(record.status()))
                        .orElseThrow(() -> releaseInvalid("scopeId"));
                yield new Target(scopeType, device.id(), null);
            }
        };
    }

    private List<UUID> affectedDeviceIds(UUID productId, Target target) {
        return switch (target.scopeType()) {
            case PRODUCT -> mapper.findUnarchivedDeviceIds(productId);
            case GROUP -> scopeMapper.findActiveMemberIds(target.scopeId());
            case DEVICE -> List.of(target.scopeId());
        };
    }

    private StrategyConfigurationMapper.StrategyVersionRecord requireVersion(UUID versionId) {
        if (versionId == null) {
            throw releaseInvalid("strategyVersionId");
        }
        return mapper.findVersionById(versionId)
                .orElseThrow(() -> new MineException(
                        VERSION_NOT_FOUND, Map.of("strategyVersionId", versionId)));
    }

    private ScopedStrategyFragment toFragment(
            StrategyConfigurationMapper.StrategyFragmentRecord record
    ) {
        return new ScopedStrategyFragment(
                record.releaseId(), StrategyScopeType.valueOf(record.scopeType()), record.scopeId(),
                record.groupPriority(), readMap(record.valuesJson())
        );
    }

    private String contentHash(UUID productId, Map<String, Object> values, UUID releaseId) {
        return resolver.resolve(List.of(new ScopedStrategyFragment(
                releaseId, StrategyScopeType.PRODUCT, productId, null, values
        ))).contentHash();
    }

    private Map<String, Object> validateValues(String strategyType, Map<String, ?> values) {
        try {
            return definition(strategyType).validate(values);
        } catch (StrategyValidationException exception) {
            throw new MineException(DEFINITION_INVALID, Map.of("violations", exception.violations()));
        }
    }

    private StrategyDefinition definition(String strategyType) {
        String normalizedType = normalizeType(strategyType);
        if (OnlineStatusStrategyDefinition.TYPE.equals(normalizedType)) {
            return new OnlineStatusStrategyDefinition();
        }
        throw new MineException(DEFINITION_INVALID, Map.of(
                "violations", List.of(new StrategyValidationException.Violation(
                        "strategyType", "不支持该策略类型"
                ))
        ));
    }

    private String normalizeType(String strategyType) {
        if (strategyType == null || strategyType.isBlank() || strategyType.length() > 64) {
            throw new MineException(DEFINITION_INVALID, Map.of(
                    "violations", List.of(new StrategyValidationException.Violation(
                            "strategyType", "策略类型不能为空且长度不能超过 64"
                    ))
            ));
        }
        return strategyType.strip().toUpperCase(Locale.ROOT);
    }

    private void validatePublishCommand(PublishCommand command) {
        if (command == null || command.strategyVersionId() == null
                || command.scopeType() == null || command.scopeId() == null) {
            throw releaseInvalid("publish");
        }
        requireText(command.reason(), "reason", 500);
        requireText(command.idempotencyKey(), "idempotencyKey", 128);
    }

    private void verifySamePublishRequest(
            StrategyConfigurationMapper.StrategyReleaseRecord existing,
            PublishCommand command,
            String reason
    ) {
        boolean sameRequest = "PUBLISH".equals(existing.action())
                && existing.strategyVersionId().equals(command.strategyVersionId())
                && existing.scopeType().equals(command.scopeType().name())
                && existing.scopeId().equals(command.scopeId())
                && existing.reason().equals(reason);
        if (!sameRequest) {
            throw new MineException(IDEMPOTENCY_CONFLICT, Map.of("releaseId", existing.id()));
        }
    }

    private void verifySameRollbackRequest(
            StrategyConfigurationMapper.StrategyReleaseRecord existing,
            UUID historicalReleaseId,
            String reason
    ) {
        boolean sameRequest = "ROLLBACK".equals(existing.action())
                && Objects.equals(existing.rollbackOfReleaseId(), historicalReleaseId)
                && existing.reason().equals(reason);
        if (!sameRequest) {
            throw new MineException(IDEMPOTENCY_CONFLICT, Map.of("releaseId", existing.id()));
        }
    }

    private void requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw releaseInvalid(field);
        }
    }

    private MineException releaseInvalid(String field) {
        return new MineException(RELEASE_INVALID, Map.of("field", field));
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JacksonException exception) {
            throw new IllegalStateException("数据库中的策略字段 JSON 无法解析", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("策略 JSON 无法序列化", exception);
        }
    }

    private UUID currentOperatorId() {
        try {
            return UUID.fromString(StpUtil.getLoginIdAsString());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private StrategyVersionView toVersionView(
            StrategyConfigurationMapper.StrategyVersionRecord record
    ) {
        return new StrategyVersionView(
                record.id(), record.productId(), record.strategyType(), record.versionNumber(),
                record.status(), readMap(record.valuesJson()), record.contentHash(), record.description(),
                record.publishedAt(), record.createdAt(), record.updatedAt(), record.version()
        );
    }

    private StrategyReleaseView toReleaseView(
            StrategyConfigurationMapper.StrategyReleaseRecord record
    ) {
        return new StrategyReleaseView(
                record.id(), record.productId(), record.strategyVersionId(), record.strategyType(),
                record.releaseSequence(), StrategyScopeType.valueOf(record.scopeType()), record.scopeId(),
                record.groupPriority(), record.action(), record.rollbackOfReleaseId(), record.contentHash()
        );
    }

    private Map<String, ?> versionSummary(StrategyVersionView version) {
        return Map.of(
                "productId", version.productId(),
                "strategyType", version.strategyType(),
                "versionNumber", version.versionNumber(),
                "status", version.status()
        );
    }

    private Map<String, ?> releaseSummary(StrategyReleaseView release) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("productId", release.productId());
        summary.put("strategyType", release.strategyType());
        summary.put("releaseSequence", release.releaseSequence());
        summary.put("scopeType", release.scopeType().name());
        summary.put("scopeId", release.scopeId());
        summary.put("action", release.action());
        summary.put("contentHash", release.contentHash());
        return Map.copyOf(summary);
    }

    /**
     * 策略草稿创建命令。
     *
     * @param productId    产品标识
     * @param strategyType 策略类型
     * @param values       局部策略字段
     * @param description  版本说明
     */
    public record CreateDraftCommand(
            UUID productId,
            String strategyType,
            Map<String, ?> values,
            String description
    ) {
    }

    /**
     * 策略草稿修改命令。
     *
     * @param values      局部策略字段
     * @param description 版本说明
     * @param version     乐观锁版本号
     */
    public record UpdateDraftCommand(Map<String, ?> values, String description, long version) {
    }

    /**
     * 策略发布命令。
     *
     * @param strategyVersionId 策略版本标识
     * @param scopeType        作用范围类型
     * @param scopeId          作用范围标识
     * @param reason           发布原因
     * @param idempotencyKey   幂等键
     */
    public record PublishCommand(
            UUID strategyVersionId,
            StrategyScopeType scopeType,
            UUID scopeId,
            String reason,
            String idempotencyKey
    ) {
    }

    /**
     * 策略版本管理视图。
     *
     * @param id            策略版本标识
     * @param productId     产品标识
     * @param strategyType  策略类型
     * @param versionNumber 版本号
     * @param status        版本状态
     * @param values        局部策略字段
     * @param contentHash   已发布内容哈希
     * @param description   版本说明
     * @param publishedAt   发布时间
     * @param createdAt     创建时间
     * @param updatedAt     更新时间
     * @param version       乐观锁版本号
     */
    public record StrategyVersionView(
            UUID id,
            UUID productId,
            String strategyType,
            long versionNumber,
            String status,
            Map<String, Object> values,
            String contentHash,
            String description,
            Instant publishedAt,
            Instant createdAt,
            Instant updatedAt,
            long version
    ) {
        /**
         * 冻结策略字段。
         */
        public StrategyVersionView {
            values = Map.copyOf(values);
        }
    }

    /**
     * 策略发布视图。
     *
     * @param id                  发布标识
     * @param productId           产品标识
     * @param strategyVersionId   策略版本标识
     * @param strategyType        策略类型
     * @param releaseSequence     产品内发布序号
     * @param scopeType           作用范围类型
     * @param scopeId             作用范围标识
     * @param groupPriority       设备组优先级快照
     * @param action              发布或回滚动作
     * @param rollbackOfReleaseId 回滚来源发布标识
     * @param contentHash         策略版本内容哈希
     */
    public record StrategyReleaseView(
            UUID id,
            UUID productId,
            UUID strategyVersionId,
            String strategyType,
            long releaseSequence,
            StrategyScopeType scopeType,
            UUID scopeId,
            Integer groupPriority,
            String action,
            UUID rollbackOfReleaseId,
            String contentHash
    ) {
    }

    /**
     * 单设备有效策略视图。
     *
     * @param deviceId     设备标识
     * @param productId    产品标识
     * @param strategyType 策略类型
     * @param valid        是否不存在冲突
     * @param values       最终字段值
     * @param sources      逐字段来源
     * @param conflicts    同优先级冲突
     * @param contentHash  最终值稳定哈希
     */
    public record EffectiveStrategyView(
            UUID deviceId,
            UUID productId,
            String strategyType,
            boolean valid,
            Map<String, Object> values,
            Map<String, EffectiveStrategyResolver.ValueSource> sources,
            List<EffectiveStrategyResolver.Conflict> conflicts,
            String contentHash
    ) {
        /**
         * 冻结有效策略集合。
         */
        public EffectiveStrategyView {
            values = Map.copyOf(values);
            sources = Map.copyOf(sources);
            conflicts = List.copyOf(conflicts);
        }
    }

    /**
     * 面向管理端的冲突明细。
     *
     * @param deviceId      冲突设备标识
     * @param field         冲突字段
     * @param groupPriority 设备组优先级
     * @param groupIds      涉及设备组标识
     */
    public record ConflictViolation(
            UUID deviceId,
            String field,
            int groupPriority,
            List<UUID> groupIds
    ) {
        /**
         * 冻结冲突设备组标识。
         */
        public ConflictViolation {
            groupIds = List.copyOf(groupIds);
        }
    }

    private record Target(StrategyScopeType scopeType, UUID scopeId, Integer groupPriority) {
    }
}
