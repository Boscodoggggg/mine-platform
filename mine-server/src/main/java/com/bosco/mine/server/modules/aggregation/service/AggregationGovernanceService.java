package com.bosco.mine.server.modules.aggregation.service;

import cn.dev33.satoken.stp.StpUtil;
import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.server.modules.aggregation.dto.AggregationExecutionResponse;
import com.bosco.mine.server.modules.aggregation.dto.InternalAggregationRecomputeClaim;
import com.bosco.mine.server.modules.aggregation.dto.AggregationRecomputeRequest;
import com.bosco.mine.server.modules.aggregation.dto.AggregationRecomputeResponse;
import com.bosco.mine.server.modules.aggregation.repository.AggregationRecomputeRepository;
import com.bosco.mine.server.modules.device.repository.DeviceMapper;
import com.bosco.mine.server.modules.device.repository.DeviceTelemetryRepository;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 管理有界、幂等且可审计的聚合重算请求及聚合运行历史。
 */
@Service
@ConditionalOnProperty(prefix = "mine.telemetry-query", name = "enabled", havingValue = "true")
public class AggregationGovernanceService {

    private static final ErrorCode REQUEST_INVALID =
            new ErrorCode("AGGREGATION-400-001", "聚合重算请求参数不合法");
    private static final ErrorCode DEVICE_NOT_FOUND =
            new ErrorCode("AGGREGATION-404-001", "聚合重算目标设备不存在");
    private static final ErrorCode IDEMPOTENCY_CONFLICT =
            new ErrorCode("AGGREGATION-409-001", "幂等键已用于其他聚合重算请求");
    private static final ErrorCode RULE_NOT_FOUND =
            new ErrorCode("AGGREGATION-409-002", "当前启用策略不存在匹配的聚合规则");
    private static final ErrorCode RUNTIME_UNAVAILABLE =
            new ErrorCode("AGGREGATION-503-001", "聚合运行事实暂时不可用");
    private static final Set<Integer> ALLOWED_BUCKET_SECONDS =
            Set.of(60, 300, 900, 3_600, 21_600, 86_400);
    private static final Duration MAX_RANGE = Duration.ofDays(31);
    private static final long MAX_BUCKETS = 10_000L;
    private static final Duration MINIMUM_CLAIM_LEASE = Duration.ofMinutes(1);
    private static final Duration MAXIMUM_CLAIM_LEASE = Duration.ofHours(1);
    private static final Pattern SECRET = Pattern.compile(
            "(?i)\\b(password|token|secret|authorization|credential)\\s*[:=]\\s*[^\\s,;]+"
    );

    private final DeviceMapper deviceMapper;
    private final DeviceTelemetryRepository telemetryRepository;
    private final AggregationRecomputeRepository recomputeRepository;
    private final OperationAuditService auditService;
    private final IdGenerator idGenerator = IdGenerator.timeOrdered();

    /**
     * 创建聚合治理应用服务。
     *
     * @param deviceMapper 设备主数据接口
     * @param telemetryRepository 遥测运行事实接口
     * @param recomputeRepository 重算请求仓储
     * @param auditService 操作审计服务
     */
    public AggregationGovernanceService(
            DeviceMapper deviceMapper,
            DeviceTelemetryRepository telemetryRepository,
            AggregationRecomputeRepository recomputeRepository,
            OperationAuditService auditService
    ) {
        this.deviceMapper = deviceMapper;
        this.telemetryRepository = telemetryRepository;
        this.recomputeRepository = recomputeRepository;
        this.auditService = auditService;
    }

    /**
     * 登记一个由 Telemetry 异步执行的显式聚合重算请求。
     *
     * @param request 重算范围和规则键
     * @param idempotencyKey 调用方幂等键
     * @return 已接受或幂等重放的请求事实
     */
    @Transactional
    public AggregationRecomputeResponse create(
            AggregationRecomputeRequest request,
            String idempotencyKey
    ) {
        NormalizedRequest normalized = validate(request, idempotencyKey);
        UUID operatorId = currentOperatorId();
        var existing = recomputeRepository.findByIdempotency(operatorId, normalized.idempotencyKey());
        if (existing.isPresent()) {
            ensureSamePayload(existing.orElseThrow(), normalized);
            return toResponse(existing.orElseThrow());
        }
        deviceMapper.findDeviceById(normalized.deviceId())
                .orElseThrow(() -> new MineException(
                        DEVICE_NOT_FOUND, Map.of("deviceId", normalized.deviceId())));
        boolean matchingRule;
        try {
            matchingRule = telemetryRepository.hasAggregationRule(
                    normalized.deviceId(), normalized.propertyId(), normalized.bucketSeconds());
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
        if (!matchingRule) {
            throw new MineException(RULE_NOT_FOUND, Map.of(
                    "deviceId", normalized.deviceId(),
                    "propertyId", normalized.propertyId(),
                    "bucketSeconds", normalized.bucketSeconds()));
        }

        AggregationRecomputeRepository.RequestRecord record =
                new AggregationRecomputeRepository.RequestRecord(
                        idGenerator.next(), normalized.deviceId(), normalized.propertyId(),
                        normalized.bucketSeconds(), normalized.from(), normalized.to(),
                        normalized.reason(), normalized.idempotencyKey(), operatorId,
                        "PENDING", Instant.now(), null, null, null);
        if (!recomputeRepository.insert(record)) {
            AggregationRecomputeRepository.RequestRecord concurrent = recomputeRepository
                    .findByIdempotency(operatorId, normalized.idempotencyKey())
                    .orElseThrow(() -> new IllegalStateException("并发幂等请求写入后无法读取"));
            ensureSamePayload(concurrent, normalized);
            return toResponse(concurrent);
        }

        auditService.recordSuccess(
                "DATA_GOVERNANCE",
                "CREATE_AGGREGATION_RECOMPUTE",
                "governance:aggregation:execute",
                "AGGREGATION_RECOMPUTE",
                record.requestId(),
                Map.of(),
                Map.of(
                        "deviceId", record.deviceId().toString(),
                        "propertyId", record.propertyId(),
                        "bucketSeconds", record.bucketSeconds(),
                        "from", record.from().toString(),
                        "to", record.to().toString(),
                        "status", record.status()
                )
        );
        return toResponse(record);
    }

    /**
     * 为 Telemetry 原子认领一个待执行或租约过期的显式重算请求。
     *
     * @param instanceId Telemetry 实例标识
     * @param leaseDuration 有界租约时长
     * @return 当前租约，暂无可认领请求时为空
     */
    @Transactional
    public Optional<InternalAggregationRecomputeClaim> claimRecomputation(
            String instanceId,
            Duration leaseDuration
    ) {
        String normalizedInstance = instanceId == null ? "" : instanceId.strip();
        if (normalizedInstance.isEmpty() || normalizedInstance.length() > 128
                || leaseDuration == null
                || leaseDuration.compareTo(MINIMUM_CLAIM_LEASE) < 0
                || leaseDuration.compareTo(MAXIMUM_CLAIM_LEASE) > 0) {
            throw invalid("claim");
        }
        return recomputeRepository.claimNext(
                        normalizedInstance, leaseDuration, idGenerator.next(), UUID.randomUUID())
                .map(record -> new InternalAggregationRecomputeClaim(
                        record.requestId(), record.executionId(), record.claimToken(),
                        record.deviceId(), record.propertyId(),
                        Duration.ofSeconds(record.bucketSeconds()), record.from(), record.to(),
                        record.leaseUntil(), record.attemptCount()));
    }

    /**
     * 使用当前租约令牌把显式重算请求收敛为成功或失败。
     *
     * @param requestId 请求标识
     * @param executionId 稳定执行标识
     * @param claimToken 当前租约令牌
     * @param status SUCCEEDED 或 FAILED
     * @param error 失败时的原始错误摘要
     * @return 当前租约是否成功完成状态迁移
     */
    @Transactional
    public boolean completeRecomputation(
            UUID requestId,
            UUID executionId,
            UUID claimToken,
            String status,
            String error
    ) {
        if (requestId == null || executionId == null || claimToken == null
                || !("SUCCEEDED".equals(status) || "FAILED".equals(status))) {
            throw invalid("completion");
        }
        String safeError = null;
        if ("FAILED".equals(status)) {
            if (error == null || error.isBlank()) {
                throw invalid("error");
            }
            safeError = safeError(error);
        } else if (error != null) {
            throw invalid("error");
        }
        return recomputeRepository.complete(
                requestId, executionId, claimToken, status, safeError);
    }

    /**
     * 查询最近的自动聚合执行事实。
     *
     * @param limit 最大返回数量
     * @return 聚合执行历史
     */
    public List<AggregationExecutionResponse> recentExecutions(int limit) {
        validateLimit(limit);
        List<DeviceTelemetryRepository.AggregationExecution> executions;
        try {
            executions = telemetryRepository.findRecentAggregationExecutions(limit);
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
        return executions.stream()
                .map(execution -> new AggregationExecutionResponse(
                        execution.executionId(), execution.instanceId(), execution.status(),
                        execution.policyCount(), execution.ruleCount(), execution.sourceWatermark(),
                        execution.bucketCount(), execution.rollupWatermark(),
                        execution.lateRowsIgnored(), execution.startedAt(), execution.completedAt(),
                        execution.lastError()))
                .toList();
    }

    /**
     * 查询最近的显式重算请求事实。
     *
     * @param limit 最大返回数量
     * @return 重算请求历史
     */
    public List<AggregationRecomputeResponse> recentRequests(int limit) {
        validateLimit(limit);
        return recomputeRepository.findRecent(limit).stream()
                .map(AggregationGovernanceService::toResponse)
                .toList();
    }

    private NormalizedRequest validate(AggregationRecomputeRequest request, String idempotencyKey) {
        if (request == null || request.deviceId() == null || request.propertyId() == null
                || request.bucket() == null || request.from() == null || request.to() == null
                || request.reason() == null || idempotencyKey == null) {
            throw invalid("required");
        }
        String propertyId = request.propertyId().trim();
        String reason = request.reason().trim();
        String normalizedKey = idempotencyKey.trim();
        if (!propertyId.matches("[A-Za-z][A-Za-z0-9_.-]{0,127}")
                || reason.isEmpty() || reason.length() > 500
                || normalizedKey.isEmpty() || normalizedKey.length() > 128
                || request.bucket().isNegative() || request.bucket().isZero()
                || request.bucket().getNano() != 0) {
            throw invalid("format");
        }
        long seconds = request.bucket().getSeconds();
        if (seconds > Integer.MAX_VALUE || !ALLOWED_BUCKET_SECONDS.contains((int) seconds)
                || !request.from().isBefore(request.to())
                || request.to().isAfter(Instant.now())
                || request.from().getNano() != 0
                || request.to().getNano() != 0
                || Math.floorMod(request.from().getEpochSecond(), seconds) != 0
                || Math.floorMod(request.to().getEpochSecond(), seconds) != 0) {
            throw invalid("range");
        }
        Duration range = Duration.between(request.from(), request.to());
        if (range.compareTo(MAX_RANGE) > 0 || range.getSeconds() / seconds > MAX_BUCKETS) {
            throw invalid("limit");
        }
        return new NormalizedRequest(
                request.deviceId(), propertyId, (int) seconds,
                request.from(), request.to(), reason, normalizedKey);
    }

    private void ensureSamePayload(
            AggregationRecomputeRepository.RequestRecord existing,
            NormalizedRequest request
    ) {
        if (!existing.deviceId().equals(request.deviceId())
                || !existing.propertyId().equals(request.propertyId())
                || existing.bucketSeconds() != request.bucketSeconds()
                || !existing.from().equals(request.from())
                || !existing.to().equals(request.to())
                || !existing.reason().equals(request.reason())) {
            throw new MineException(
                    IDEMPOTENCY_CONFLICT, Map.of("requestId", existing.requestId()));
        }
    }

    private void validateLimit(int limit) {
        if (limit < 1 || limit > 100) {
            throw invalid("limit");
        }
    }

    private UUID currentOperatorId() {
        try {
            return UUID.fromString(StpUtil.getLoginIdAsString());
        } catch (RuntimeException exception) {
            throw invalid("operator");
        }
    }

    private static MineException invalid(String field) {
        return new MineException(REQUEST_INVALID, Map.of("field", field));
    }

    private static MineException unavailable(RuntimeException cause) {
        MineException exception = new MineException(RUNTIME_UNAVAILABLE, Map.of());
        exception.initCause(cause);
        return exception;
    }

    private static String safeError(String value) {
        String singleLine = value.replaceAll("[\\r\\n\\t]+", " ").strip();
        String redacted = SECRET.matcher(singleLine).replaceAll("$1=[REDACTED]");
        return redacted.length() <= 2_000 ? redacted : redacted.substring(0, 2_000);
    }

    private static AggregationRecomputeResponse toResponse(
            AggregationRecomputeRepository.RequestRecord record
    ) {
        return new AggregationRecomputeResponse(
                record.requestId(), record.deviceId(), record.propertyId(),
                Duration.ofSeconds(record.bucketSeconds()), record.from(), record.to(),
                record.reason(), record.idempotencyKey(), record.status(), record.requestedBy(),
                record.createdAt(), record.completedAt(), record.executionId(), record.lastError());
    }

    private record NormalizedRequest(
            UUID deviceId,
            String propertyId,
            int bucketSeconds,
            Instant from,
            Instant to,
            String reason,
            String idempotencyKey
    ) {
    }
}
