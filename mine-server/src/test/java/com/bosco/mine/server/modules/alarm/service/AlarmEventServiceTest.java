package com.bosco.mine.server.modules.alarm.service;

import com.bosco.mine.common.error.MineException;
import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.common.time.MineClock;
import com.bosco.mine.messaging.alarm.AlarmEvidencePayload;
import com.bosco.mine.messaging.alarm.AlarmRaisedPayload;
import com.bosco.mine.messaging.alarm.AlarmRecoveredPayload;
import com.bosco.mine.server.modules.alarm.AlarmMetrics;
import com.bosco.mine.server.modules.alarm.domain.AlarmStatus;
import com.bosco.mine.server.modules.alarm.messaging.InvalidAlarmEventMessageException;
import com.bosco.mine.server.modules.alarm.repository.AlarmEventRepository;
import com.bosco.mine.server.modules.alarm.repository.AlarmEventRepository.AlarmEventRecord;
import com.bosco.mine.server.modules.mine.service.MineScopeAuthorizer;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验告警乱序聚合、幂等处置和事实状态推导。
 */
class AlarmEventServiceTest {
    private static final UUID ALARM_ID = UUID.fromString("019f64e5-a000-7000-8000-000000000401");
    private static final UUID DEVICE_ID = UUID.fromString("019f64e5-a000-7000-8000-000000000402");
    private static final UUID SNAPSHOT_ID = UUID.fromString("019f64e5-a000-7000-8000-000000000403");
    private static final UUID EVENT_ID = UUID.fromString("019f64e5-a000-7000-8000-000000000404");
    private static final UUID OPERATOR_ID = UUID.fromString("019f64e5-a000-7000-8000-000000000405");
    private static final UUID SITE_ID = UUID.fromString("019f64e5-a000-7000-8000-000000000406");
    private static final Instant RAISED_AT = Instant.parse("2026-07-17T01:00:00Z");
    private static final Instant RECOVERED_AT = Instant.parse("2026-07-17T01:05:00Z");
    private static final Instant NOW = Instant.parse("2026-07-17T02:00:00Z");
    private static final String HASH = "sha256:" + "a".repeat(64);
    private static final String EVIDENCE = """
            {"observedValue":90,"operator":"GT","threshold":80,"recoverThreshold":75,"qualityRuleCode":null}
            """;

    private AlarmEventRepository repository;
    private OperationAuditService auditService;
    private AlarmMetrics metrics;
    private MineScopeAuthorizer mineScopeAuthorizer;
    private AlarmEventService service;

    @BeforeEach
    void 创建告警服务测试环境() {
        repository = mock(AlarmEventRepository.class);
        auditService = mock(OperationAuditService.class);
        metrics = mock(AlarmMetrics.class);
        mineScopeAuthorizer = mock(MineScopeAuthorizer.class);
        service = new AlarmEventService(
                repository,
                auditService,
                MineClock.fixed(NOW, ZoneOffset.UTC),
                IdGenerator.timeOrdered(),
                metrics,
                mineScopeAuthorizer
        );
        when(repository.recordInbox(any(UUID.class))).thenReturn(true);
        when(repository.findDeviceProductKey(DEVICE_ID)).thenReturn(Optional.of("haul-truck"));
    }

    @Test
    void 恢复先到时应独立创建已恢复告警() {
        when(repository.lockById(ALARM_ID)).thenReturn(Optional.empty());

        assertThat(service.applyRecovered(EVENT_ID, recovered())).isTrue();

        verify(repository).insertRecovered(
                org.mockito.ArgumentMatchers.eq(EVENT_ID),
                org.mockito.ArgumentMatchers.eq(recovered()),
                anyString(),
                org.mockito.ArgumentMatchers.eq(NOW)
        );
        verify(repository, never()).insertRaised(any(), any(), anyString(), any());
    }

    @Test
    void 触发后到时只补齐触发证据不得回滚恢复事实() {
        when(repository.lockById(ALARM_ID)).thenReturn(Optional.of(record(
                null, EVENT_ID, RECOVERED_AT, 0, null, null, null)));

        UUID lateEventId = UUID.fromString("019f64e5-a000-7000-8000-000000000406");
        assertThat(service.applyRaised(lateEventId, raised())).isTrue();

        verify(repository).supplementRaised(
                org.mockito.ArgumentMatchers.eq(ALARM_ID),
                org.mockito.ArgumentMatchers.eq(lateEventId), anyString(),
                org.mockito.ArgumentMatchers.eq(NOW));
        verify(repository, never()).applyRecovery(any(), any(), any(), anyString(), anyString(), any());
    }

    @Test
    void 同一周期身份冲突应作为永久消息错误拒绝() {
        AlarmEventRecord conflicting = new AlarmEventRecord(
                ALARM_ID, UUID.randomUUID(), "truck-001", "一号运输车", "haul-truck",
                "engine-hot", "发动机过热", "NUMERIC", "CRITICAL", "engineTemp",
                7, SNAPSHOT_ID, HASH, RAISED_AT, EVIDENCE, EVENT_ID,
                null, null, null, null, null, null, null,
                null, null, null, null, 0);
        when(repository.lockById(ALARM_ID)).thenReturn(Optional.of(conflicting));

        assertThatThrownBy(() -> service.applyRaised(EVENT_ID, raised()))
                .isInstanceOf(InvalidAlarmEventMessageException.class)
                .hasMessageContaining("身份");
    }

    @Test
    void 重复事件标识应由Inbox直接短路() {
        when(repository.recordInbox(EVENT_ID)).thenReturn(false);

        assertThat(service.applyRaised(EVENT_ID, raised())).isFalse();

        verify(repository, never()).lockOccurrence(any());
    }

    @Test
    void 超大页码应在进入数据库前拒绝() {
        assertThatThrownBy(() -> service.findGlobal(
                null, null, null, null, null, null, null, null, null, 1_000_001, 20))
                .isInstanceOfSatisfying(MineException.class,
                        exception -> assertThat(exception.errorCode().code()).isEqualTo("ALARM-400-001"));
        verify(repository, never()).find(any(), anyInt(), anyInt());
    }

    @Test
    void 矿区告警查询应先校验作用域并把矿区写入仓储条件() {
        service.findForSite(
                SITE_ID, null, false, null, null, null, null,
                null, null, null, 1, 20);

        verify(mineScopeAuthorizer).requireSite(SITE_ID);
        verify(repository).find(
                org.mockito.ArgumentMatchers.argThat(query -> SITE_ID.equals(query.siteId())),
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq(20));
    }

    @Test
    void 全局告警中心查询不应伪装成矿区作用域查询() {
        service.findGlobal(
                null, false, null, null, null, null, null,
                null, null, 1, 20);

        verify(mineScopeAuthorizer, never()).requireSite(any());
        verify(repository).find(
                org.mockito.ArgumentMatchers.argThat(query -> query.siteId() == null),
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq(20));
    }

    @Test
    void 首次确认应写唯一事实推进版本并记录脱敏审计() {
        AlarmEventRecord current = record(EVENT_ID, null, null, 3, null, null, null);
        AlarmEventRecord acknowledged = record(EVENT_ID, null, null, 4, NOW, null, null);
        when(repository.lockById(ALARM_ID)).thenReturn(Optional.of(current));
        when(repository.acknowledge(ALARM_ID, OPERATOR_ID, "已通知调度", NOW, 3)).thenReturn(1);
        when(repository.findById(ALARM_ID)).thenReturn(Optional.of(acknowledged));

        var response = service.acknowledge(
                ALARM_ID, OPERATOR_ID, "  已通知调度  ", "ack-001", 3);

        assertThat(response.status()).isEqualTo(AlarmStatus.ACTIVE_ACKNOWLEDGED);
        verify(repository).insertAcknowledgement(
                any(UUID.class), org.mockito.ArgumentMatchers.eq(ALARM_ID),
                org.mockito.ArgumentMatchers.eq(OPERATOR_ID), org.mockito.ArgumentMatchers.eq("已通知调度"),
                org.mockito.ArgumentMatchers.eq("ack-001"), org.mockito.ArgumentMatchers.eq(NOW));
        verify(auditService).recordSuccess(
                org.mockito.ArgumentMatchers.eq("ALARM"),
                org.mockito.ArgumentMatchers.eq("ACKNOWLEDGE"),
                org.mockito.ArgumentMatchers.eq("alarm:event:ack"),
                org.mockito.ArgumentMatchers.eq("ALARM_EVENT"),
                org.mockito.ArgumentMatchers.eq(ALARM_ID), any(), any());
        verify(metrics).recordDisposition(
                AlarmMetrics.DispositionAction.ACKNOWLEDGE,
                AlarmMetrics.DispositionOutcome.APPLIED);
    }

    @Test
    void 确认结果指标应在事务提交后记录且回滚时记为失败() {
        AlarmEventRecord current = record(EVENT_ID, null, null, 3, null, null, null);
        AlarmEventRecord acknowledged = record(EVENT_ID, null, null, 4, NOW, null, null);
        when(repository.lockById(ALARM_ID)).thenReturn(Optional.of(current));
        when(repository.acknowledge(ALARM_ID, OPERATOR_ID, "已通知调度", NOW, 3)).thenReturn(1);
        when(repository.findById(ALARM_ID)).thenReturn(Optional.of(acknowledged));
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.acknowledge(ALARM_ID, OPERATOR_ID, "已通知调度", "ack-commit", 3);

            verify(metrics, never()).recordDisposition(
                    AlarmMetrics.DispositionAction.ACKNOWLEDGE,
                    AlarmMetrics.DispositionOutcome.APPLIED);
            var synchronizations = TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);
            synchronizations.forEach(TransactionSynchronization::afterCommit);
            synchronizations.forEach(synchronization -> synchronization.afterCompletion(
                    TransactionSynchronization.STATUS_COMMITTED));

            verify(metrics).recordDisposition(
                    AlarmMetrics.DispositionAction.ACKNOWLEDGE,
                    AlarmMetrics.DispositionOutcome.APPLIED);
            verify(metrics, never()).recordDisposition(
                    AlarmMetrics.DispositionAction.ACKNOWLEDGE,
                    AlarmMetrics.DispositionOutcome.FAILED);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void 确认事务最终回滚时不得记录成功结果() {
        AlarmEventRecord current = record(EVENT_ID, null, null, 3, null, null, null);
        AlarmEventRecord acknowledged = record(EVENT_ID, null, null, 4, NOW, null, null);
        when(repository.lockById(ALARM_ID)).thenReturn(Optional.of(current));
        when(repository.acknowledge(ALARM_ID, OPERATOR_ID, "已通知调度", NOW, 3)).thenReturn(1);
        when(repository.findById(ALARM_ID)).thenReturn(Optional.of(acknowledged));
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.acknowledge(ALARM_ID, OPERATOR_ID, "已通知调度", "ack-rollback", 3);
            var synchronizations = TransactionSynchronizationManager.getSynchronizations();

            synchronizations.forEach(synchronization -> synchronization.afterCompletion(
                    TransactionSynchronization.STATUS_ROLLED_BACK));

            verify(metrics, never()).recordDisposition(
                    AlarmMetrics.DispositionAction.ACKNOWLEDGE,
                    AlarmMetrics.DispositionOutcome.APPLIED);
            verify(metrics).recordDisposition(
                    AlarmMetrics.DispositionAction.ACKNOWLEDGE,
                    AlarmMetrics.DispositionOutcome.FAILED);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void 已确认告警使用不同幂等键重试也不得重复写事实和审计() {
        AlarmEventRecord acknowledged = record(EVENT_ID, null, null, 4, NOW, null, null);
        when(repository.lockById(ALARM_ID)).thenReturn(Optional.of(acknowledged));

        assertThat(service.acknowledge(ALARM_ID, OPERATOR_ID, "再次确认", "ack-002", 1).version())
                .isEqualTo(4);

        verify(repository, never()).insertAcknowledgement(any(), any(), any(), anyString(), anyString(), any());
        verify(auditService, never()).recordSuccess(
                anyString(), anyString(), anyString(), anyString(), any(), any(), any());
        verify(metrics).recordDisposition(
                AlarmMetrics.DispositionAction.ACKNOWLEDGE,
                AlarmMetrics.DispositionOutcome.DUPLICATE);
    }

    @Test
    void 活动告警不得关闭() {
        when(repository.lockById(ALARM_ID)).thenReturn(Optional.of(
                record(EVENT_ID, null, null, 2, null, null, null)));

        assertThatThrownBy(() -> service.close(
                ALARM_ID, OPERATOR_ID, "关闭", "close-001", 2))
                .isInstanceOfSatisfying(MineException.class,
                        exception -> assertThat(exception.errorCode().code()).isEqualTo("ALARM-409-002"));
        verify(repository, never()).close(any(), any(), anyString(), anyString(), any(), anyLong());
        verify(metrics).recordDisposition(
                AlarmMetrics.DispositionAction.CLOSE,
                AlarmMetrics.DispositionOutcome.INVALID);
    }

    @Test
    void 处置版本过期应返回稳定冲突错误() {
        when(repository.lockById(ALARM_ID)).thenReturn(Optional.of(
                record(EVENT_ID, EVENT_ID, RECOVERED_AT, 2, null, null, null)));

        assertThatThrownBy(() -> service.close(
                ALARM_ID, OPERATOR_ID, "关闭", "close-001", 1))
                .isInstanceOfSatisfying(MineException.class,
                        exception -> assertThat(exception.errorCode().code()).isEqualTo("ALARM-409-001"));
        verify(repository, never()).close(any(), any(), anyString(), anyString(), any(), anyLong());
        verify(metrics).recordDisposition(
                AlarmMetrics.DispositionAction.CLOSE,
                AlarmMetrics.DispositionOutcome.CONFLICT);
    }

    @Test
    void 已恢复告警关闭应推导为关闭状态() {
        AlarmEventRecord recovered = record(EVENT_ID, EVENT_ID, RECOVERED_AT, 2, null, null, null);
        AlarmEventRecord closed = record(EVENT_ID, EVENT_ID, RECOVERED_AT, 3, null, NOW, "close-001");
        when(repository.lockById(ALARM_ID)).thenReturn(Optional.of(recovered));
        when(repository.close(ALARM_ID, OPERATOR_ID, "现场复核完成", "close-001", NOW, 2))
                .thenReturn(1);
        when(repository.findById(ALARM_ID)).thenReturn(Optional.of(closed));

        assertThat(service.close(
                ALARM_ID, OPERATOR_ID, "现场复核完成", "close-001", 2).status())
                .isEqualTo(AlarmStatus.CLOSED);
        verify(auditService).recordSuccess(
                org.mockito.ArgumentMatchers.eq("ALARM"),
                org.mockito.ArgumentMatchers.eq("CLOSE"),
                org.mockito.ArgumentMatchers.eq("alarm:event:close"),
                org.mockito.ArgumentMatchers.eq("ALARM_EVENT"),
                org.mockito.ArgumentMatchers.eq(ALARM_ID), any(), any());
        verify(metrics).recordDisposition(
                AlarmMetrics.DispositionAction.CLOSE,
                AlarmMetrics.DispositionOutcome.APPLIED);
    }

    private AlarmRaisedPayload raised() {
        return new AlarmRaisedPayload(
                ALARM_ID, DEVICE_ID, "haul-truck", "engine-hot", "发动机过热",
                "NUMERIC", "CRITICAL", "engineTemp",
                new AlarmEvidencePayload(
                        new BigDecimal("90"), "GT", new BigDecimal("80"),
                        new BigDecimal("75"), null),
                7, SNAPSHOT_ID, HASH, RAISED_AT
        );
    }

    private AlarmRecoveredPayload recovered() {
        return new AlarmRecoveredPayload(
                ALARM_ID, DEVICE_ID, "haul-truck", "engine-hot", "发动机过热",
                "NUMERIC", "CRITICAL", "engineTemp",
                new AlarmEvidencePayload(
                        new BigDecimal("70"), "GT", new BigDecimal("80"),
                        new BigDecimal("75"), null),
                7, SNAPSHOT_ID, HASH, RAISED_AT, RECOVERED_AT, "CONDITION_CLEARED"
        );
    }

    private AlarmEventRecord record(
            UUID raisedEventId,
            UUID recoveredEventId,
            Instant recoveredAt,
            long version,
            Instant acknowledgedAt,
            Instant closedAt,
            String closeKey
    ) {
        return new AlarmEventRecord(
                ALARM_ID, DEVICE_ID, "truck-001", "一号运输车", "haul-truck",
                "engine-hot", "发动机过热", "NUMERIC", "CRITICAL", "engineTemp",
                7, SNAPSHOT_ID, HASH, RAISED_AT,
                raisedEventId == null ? null : EVIDENCE, raisedEventId,
                recoveredAt, recoveredEventId == null ? null : EVIDENCE, recoveredEventId,
                recoveredEventId == null ? null : "CONDITION_CLEARED",
                acknowledgedAt, acknowledgedAt == null ? null : OPERATOR_ID,
                acknowledgedAt == null ? null : "已通知调度",
                closedAt, closedAt == null ? null : OPERATOR_ID,
                closedAt == null ? null : "现场复核完成", closeKey, version
        );
    }
}
