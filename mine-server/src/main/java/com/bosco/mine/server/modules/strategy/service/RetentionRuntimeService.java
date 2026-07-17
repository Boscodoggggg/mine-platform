package com.bosco.mine.server.modules.strategy.service;

import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.model.strategy.RetentionStrategyDefinition;
import com.bosco.mine.model.strategy.StrategyScopeType;
import com.bosco.mine.model.strategy.StrategyValidationException;
import com.bosco.mine.server.modules.strategy.repository.RetentionRuntimeRepository;
import com.bosco.mine.server.modules.strategy.repository.StrategyConfigurationMapper;
import com.bosco.mine.server.modules.strategy.repository.StrategyScopeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 校验数据保留策略与作用域，并编排只读影响预览和执行历史查询。
 */
@Service
public class RetentionRuntimeService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RetentionRuntimeService.class);
    private static final ErrorCode POLICY_INVALID =
            new ErrorCode("RETENTION-422-001", "数据保留策略未通过白名单校验");
    private static final ErrorCode SCOPE_INVALID =
            new ErrorCode("RETENTION-422-002", "数据保留预览作用范围不合法");
    private static final ErrorCode QUERY_INVALID =
            new ErrorCode("RETENTION-422-003", "数据保留运行查询参数不合法");
    private static final ErrorCode RUNTIME_UNAVAILABLE =
            new ErrorCode("RETENTION-503-001", "数据保留运行查询暂时不可用");
    private static final long PREVIEW_COUNT_LIMIT = 1_000_000L;

    private final RetentionRuntimeRepository repository;
    private final StrategyConfigurationMapper strategyMapper;
    private final StrategyScopeMapper scopeMapper;
    private final RetentionStrategyDefinition definition = new RetentionStrategyDefinition();

    /**
     * 创建数据保留运行查询服务。
     *
     * @param repository     TimescaleDB 只读运行仓储
     * @param strategyMapper 策略与设备查询接口
     * @param scopeMapper    产品和设备组查询接口
     */
    public RetentionRuntimeService(
            RetentionRuntimeRepository repository,
            StrategyConfigurationMapper strategyMapper,
            StrategyScopeMapper scopeMapper
    ) {
        this.repository = repository;
        this.strategyMapper = strategyMapper;
        this.scopeMapper = scopeMapper;
    }

    /**
     * 使用拟发布策略预览目标作用域中过期数据影响。
     *
     * @param command 预览命令
     * @return 带统计时点和封顶标记的影响预览
     */
    public RetentionPreviewView preview(PreviewCommand command) {
        validateCommand(command);
        PolicyView policy = validatePolicy(command.policy());
        List<UUID> affectedDeviceIds = resolveAffectedDeviceIds(command);
        try {
            RetentionRuntimeRepository.PreviewSnapshot snapshot = repository.preview(
                    affectedDeviceIds,
                    policy.rawTelemetryDays(),
                    policy.qualityEvidenceDays(),
                    PREVIEW_COUNT_LIMIT
            );
            return new RetentionPreviewView(
                    snapshot.observedAt(),
                    affectedDeviceIds.size(),
                    policy,
                    toView(snapshot.rawTelemetry()),
                    toView(snapshot.qualityEvidence())
            );
        } catch (RuntimeException exception) {
            throw unavailable("preview", exception);
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
            return repository.findExecutions(limit).stream()
                    .map(this::toView)
                    .toList();
        } catch (RuntimeException exception) {
            throw unavailable("executions", exception);
        }
    }

    private void validateCommand(PreviewCommand command) {
        if (command == null || command.productId() == null
                || command.scopeType() == null || command.scopeId() == null) {
            throw new MineException(SCOPE_INVALID, Map.of("field", "scope"));
        }
    }

    @SuppressWarnings("unchecked")
    private PolicyView validatePolicy(Map<String, Object> policy) {
        try {
            Map<String, Object> values = definition.validate(
                    policy == null ? null : Map.of(RetentionStrategyDefinition.POLICY, policy));
            Map<String, Object> normalized = (Map<String, Object>) values.get(
                    RetentionStrategyDefinition.POLICY);
            return new PolicyView(
                    (Boolean) normalized.get(RetentionStrategyDefinition.ENABLED),
                    (String) normalized.get(RetentionStrategyDefinition.RETENTION_CLASS),
                    (Integer) normalized.get(RetentionStrategyDefinition.RAW_TELEMETRY_DAYS),
                    (Integer) normalized.get(RetentionStrategyDefinition.QUALITY_EVIDENCE_DAYS)
            );
        } catch (StrategyValidationException exception) {
            throw new MineException(POLICY_INVALID, Map.of(
                    "violations", exception.violations()));
        }
    }

    private List<UUID> resolveAffectedDeviceIds(PreviewCommand command) {
        return switch (command.scopeType()) {
            case PRODUCT -> resolveProduct(command.productId(), command.scopeId());
            case GROUP -> resolveGroup(command.productId(), command.scopeId());
            case DEVICE -> resolveDevice(command.productId(), command.scopeId());
        };
    }

    private List<UUID> resolveProduct(UUID productId, UUID scopeId) {
        if (!productId.equals(scopeId) || !scopeMapper.isActiveProduct(productId)) {
            throw invalidScope(scopeId);
        }
        return List.copyOf(strategyMapper.findUnarchivedDeviceIds(productId));
    }

    private List<UUID> resolveGroup(UUID productId, UUID groupId) {
        StrategyScopeMapper.DeviceGroupRecord group = scopeMapper.findGroupById(groupId)
                .filter(record -> productId.equals(record.productId())
                        && "ACTIVE".equals(record.status()))
                .orElseThrow(() -> invalidScope(groupId));
        return scopeMapper.findActiveMembers(group.id()).stream()
                .filter(member -> !"ARCHIVED".equals(member.status()))
                .map(StrategyScopeMapper.DeviceGroupMemberRecord::id)
                .toList();
    }

    private List<UUID> resolveDevice(UUID productId, UUID deviceId) {
        StrategyConfigurationMapper.DeviceContextRecord device =
                strategyMapper.findDeviceContext(deviceId)
                        .filter(record -> productId.equals(record.productId())
                                && !"ARCHIVED".equals(record.status()))
                        .orElseThrow(() -> invalidScope(deviceId));
        return List.of(device.id());
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

    private MineException invalidScope(UUID scopeId) {
        return new MineException(SCOPE_INVALID, Map.of("scopeId", scopeId));
    }

    private MineException unavailable(String operation, RuntimeException exception) {
        LOGGER.warn("数据保留只读运行查询失败: operation={}, exceptionType={}",
                operation, exception.getClass().getSimpleName());
        return new MineException(RUNTIME_UNAVAILABLE, Map.of());
    }

    /**
     * 数据保留影响预览命令。
     *
     * @param productId 产品标识
     * @param scopeType 作用域类型
     * @param scopeId   作用域标识
     * @param policy    拟发布的原子保留策略对象
     */
    public record PreviewCommand(
            UUID productId,
            StrategyScopeType scopeType,
            UUID scopeId,
            Map<String, Object> policy
    ) {
    }

    /**
     * 已规范化的数据保留策略。
     *
     * @param enabled              是否启用执行
     * @param retentionClass       保留等级
     * @param rawTelemetryDays     原始遥测保留天数
     * @param qualityEvidenceDays  质量证据保留天数
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
     * @param observedAt          数据库统计时点
     * @param affectedDeviceCount 受影响设备数
     * @param policy              已规范化策略
     * @param rawTelemetry        原始数值遥测影响
     * @param qualityEvidence     质量证据影响
     */
    public record RetentionPreviewView(
            Instant observedAt,
            long affectedDeviceCount,
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
}
