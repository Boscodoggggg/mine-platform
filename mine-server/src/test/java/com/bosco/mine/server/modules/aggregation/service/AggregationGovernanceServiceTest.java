package com.bosco.mine.server.modules.aggregation.service;

import cn.dev33.satoken.stp.StpUtil;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.modules.aggregation.dto.AggregationRecomputeRequest;
import com.bosco.mine.server.modules.aggregation.repository.AggregationRecomputeRepository;
import com.bosco.mine.server.modules.device.model.Device;
import com.bosco.mine.server.modules.device.repository.DeviceMapper;
import com.bosco.mine.server.modules.device.repository.DeviceTelemetryRepository;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验聚合重算请求的范围、幂等、当前规则、审计和运行历史边界。
 */
class AggregationGovernanceServiceTest {

    private static final UUID OPERATOR_ID =
            UUID.fromString("019f7100-0000-7000-8000-000000000001");
    private static final UUID DEVICE_ID =
            UUID.fromString("019f7100-0000-7000-8000-000000000002");
    private static final Instant FROM = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-07-02T00:00:00Z");

    @Test
    void 合法重算应只登记待执行事实并记录脱敏操作审计() {
        Fixture fixture = fixture();
        when(fixture.telemetry.hasAggregationRule(DEVICE_ID, "engine.rpm", 300))
                .thenReturn(true);
        when(fixture.repository.findByIdempotency(OPERATOR_ID, "recompute-001"))
                .thenReturn(Optional.empty());
        when(fixture.repository.insert(any())).thenReturn(true);

        try (var stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsString).thenReturn(OPERATOR_ID.toString());
            var result = fixture.service.create(
                    new AggregationRecomputeRequest(
                            DEVICE_ID, "engine.rpm", Duration.ofMinutes(5),
                            FROM, TO, "补算弱网恢复前历史"),
                    "recompute-001"
            );

            assertThat(result.status()).isEqualTo("PENDING");
            assertThat(result.deviceId()).isEqualTo(DEVICE_ID);
            assertThat(result.bucket()).isEqualTo(Duration.ofMinutes(5));
            verify(fixture.repository).insert(any(AggregationRecomputeRepository.RequestRecord.class));
            verify(fixture.audit).recordSuccess(
                    eq("DATA_GOVERNANCE"),
                    eq("CREATE_AGGREGATION_RECOMPUTE"),
                    eq("governance:aggregation:execute"),
                    eq("AGGREGATION_RECOMPUTE"),
                    eq(result.requestId()),
                    eq(Map.of()),
                    any()
            );
        }
    }

    @Test
    void 相同幂等键只能重放相同请求且不得重复审计() {
        Fixture fixture = fixture();
        AggregationRecomputeRepository.RequestRecord existing = record(
                UUID.fromString("019f7100-0000-7000-8000-000000000010"),
                DEVICE_ID, "engine.rpm", 300, FROM, TO, "补算弱网恢复前历史",
                "recompute-001", OPERATOR_ID
        );
        when(fixture.repository.findByIdempotency(OPERATOR_ID, "recompute-001"))
                .thenReturn(Optional.of(existing));

        try (var stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsString).thenReturn(OPERATOR_ID.toString());
            var replay = fixture.service.create(
                    new AggregationRecomputeRequest(
                            DEVICE_ID, "engine.rpm", Duration.ofMinutes(5),
                            FROM, TO, "补算弱网恢复前历史"),
                    "recompute-001"
            );
            assertThat(replay.requestId()).isEqualTo(existing.requestId());

            assertThatThrownBy(() -> fixture.service.create(
                    new AggregationRecomputeRequest(
                            DEVICE_ID, "engine.rpm", Duration.ofMinutes(5),
                            FROM, TO.plusSeconds(300), "补算弱网恢复前历史"),
                    "recompute-001"
            )).isInstanceOfSatisfying(MineException.class, exception ->
                    assertThat(exception.errorCode().code())
                            .isEqualTo("AGGREGATION-409-001"));
        }
        verify(fixture.repository, never()).insert(any());
        verify(fixture.telemetry, never()).hasAggregationRule(any(), any(), anyInt());
        verify(fixture.audit, never()).recordSuccess(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 遥测运行库不可用时应返回稳定服务不可用错误() {
        Fixture fixture = fixture();
        when(fixture.telemetry.hasAggregationRule(DEVICE_ID, "engine.rpm", 300))
                .thenThrow(new IllegalStateException("timescale password=secret"));

        try (var stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsString).thenReturn(OPERATOR_ID.toString());
            assertThatThrownBy(() -> fixture.service.create(
                    new AggregationRecomputeRequest(
                            DEVICE_ID, "engine.rpm", Duration.ofMinutes(5),
                            FROM, TO, "运行库故障"),
                    "recompute-unavailable"
            )).isInstanceOfSatisfying(MineException.class, exception ->
                    assertThat(exception.errorCode().code())
                            .isEqualTo("AGGREGATION-503-001"));
        }
    }

    @Test
    void 重算必须匹配当前规则并使用对齐且有界的过去时间范围() {
        Fixture fixture = fixture();
        when(fixture.telemetry.hasAggregationRule(DEVICE_ID, "engine.rpm", 300))
                .thenReturn(false);

        try (var stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsString).thenReturn(OPERATOR_ID.toString());
            assertThatThrownBy(() -> fixture.service.create(
                    new AggregationRecomputeRequest(
                            DEVICE_ID, "engine.rpm", Duration.ofMinutes(5),
                            FROM.plusSeconds(1), TO, "边界未对齐"),
                    "recompute-002"
            )).isInstanceOfSatisfying(MineException.class, exception ->
                    assertThat(exception.errorCode().code())
                            .isEqualTo("AGGREGATION-400-001"));
            assertThatThrownBy(() -> fixture.service.create(
                    new AggregationRecomputeRequest(
                            DEVICE_ID, "engine.rpm", Duration.ofMinutes(5),
                            FROM, TO, "当前没有规则"),
                    "recompute-003"
            )).isInstanceOfSatisfying(MineException.class, exception ->
                    assertThat(exception.errorCode().code())
                            .isEqualTo("AGGREGATION-409-002"));
        }
        verify(fixture.repository, never()).insert(any());
    }

    @Test
    void 应有界返回聚合执行历史和重算请求历史() {
        Fixture fixture = fixture();
        UUID executionId = UUID.fromString("019f7100-0000-7000-8000-000000000020");
        when(fixture.telemetry.findRecentAggregationExecutions(20)).thenReturn(List.of(
                new DeviceTelemetryRepository.AggregationExecution(
                        executionId, "telemetry-a", "PARTIAL", 3, 5, 88,
                        10, 12L, 2, FROM, TO, null)
        ));
        when(fixture.repository.findRecent(20)).thenReturn(List.of(record(
                UUID.fromString("019f7100-0000-7000-8000-000000000021"),
                DEVICE_ID, "engine.rpm", 300, FROM, TO,
                "补算", "recompute-020", OPERATOR_ID
        )));

        assertThat(fixture.service.recentExecutions(20)).singleElement()
                .satisfies(view -> {
                    assertThat(view.executionId()).isEqualTo(executionId);
                    assertThat(view.status()).isEqualTo("PARTIAL");
                    assertThat(view.bucketCount()).isEqualTo(10);
                });
        assertThat(fixture.service.recentRequests(20)).singleElement()
                .satisfies(view -> assertThat(view.idempotencyKey())
                        .isEqualTo("recompute-020"));
    }

    @Test
    void 内部租约应映射稳定执行标识且失败回写必须脱敏() {
        Fixture fixture = fixture();
        UUID requestId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        UUID claimToken = UUID.randomUUID();
        when(fixture.repository.claimNext(
                eq("telemetry-a"), eq(Duration.ofMinutes(5)), any(), any()))
                .thenReturn(Optional.of(new AggregationRecomputeRepository.ClaimRecord(
                        requestId, executionId, claimToken, DEVICE_ID, "engine.rpm", 300,
                        FROM, TO, FROM.plusSeconds(300), 2)));

        assertThat(fixture.service.claimRecomputation(" telemetry-a ", Duration.ofMinutes(5)))
                .get()
                .satisfies(claim -> {
                    assertThat(claim.executionId()).isEqualTo(executionId);
                    assertThat(claim.claimToken()).isEqualTo(claimToken);
                    assertThat(claim.attemptCount()).isEqualTo(2);
                });
        when(fixture.repository.complete(
                requestId, executionId, claimToken, "FAILED", "password=[REDACTED] failed"))
                .thenReturn(true);

        assertThat(fixture.service.completeRecomputation(
                requestId, executionId, claimToken, "FAILED",
                "password=secret\nfailed")).isTrue();
        verify(fixture.repository).complete(
                requestId, executionId, claimToken, "FAILED", "password=[REDACTED] failed");
    }

    private Fixture fixture() {
        DeviceMapper mapper = mock(DeviceMapper.class);
        when(mapper.findDeviceById(DEVICE_ID)).thenReturn(Optional.of(new Device(
                DEVICE_ID, UUID.randomUUID(), "truck-001", "一号矿卡", "ENABLED",
                null, FROM, FROM, 0
        )));
        DeviceTelemetryRepository telemetry = mock(DeviceTelemetryRepository.class);
        AggregationRecomputeRepository repository = mock(AggregationRecomputeRepository.class);
        OperationAuditService audit = mock(OperationAuditService.class);
        return new Fixture(
                new AggregationGovernanceService(mapper, telemetry, repository, audit),
                telemetry, repository, audit
        );
    }

    private AggregationRecomputeRepository.RequestRecord record(
            UUID id,
            UUID deviceId,
            String propertyId,
            int bucketSeconds,
            Instant from,
            Instant to,
            String reason,
            String idempotencyKey,
            UUID requestedBy
    ) {
        return new AggregationRecomputeRepository.RequestRecord(
                id, deviceId, propertyId, bucketSeconds, from, to, reason,
                idempotencyKey, requestedBy, "PENDING", FROM, null, null, null
        );
    }

    private record Fixture(
            AggregationGovernanceService service,
            DeviceTelemetryRepository telemetry,
            AggregationRecomputeRepository repository,
            OperationAuditService audit
    ) {
    }
}
