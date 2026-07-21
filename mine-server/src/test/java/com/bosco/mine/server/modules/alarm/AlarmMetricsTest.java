package com.bosco.mine.server.modules.alarm;

import com.bosco.mine.server.modules.alarm.repository.AlarmEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 校验告警消费与活动告警指标只使用有界标签。
 */
class AlarmMetricsTest {

    @Test
    void 应按固定结果计数并按固定等级暴露活动告警() {
        AlarmEventRepository repository = mock(AlarmEventRepository.class);
        when(repository.countActiveBySeverity("CRITICAL")).thenReturn(7L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AlarmMetrics metrics = new AlarmMetrics(registry, repository);

        metrics.recordProcessed();
        metrics.recordDuplicate();
        metrics.recordRejected();
        metrics.recordRequeued();
        metrics.recordDisposition(
                AlarmMetrics.DispositionAction.ACKNOWLEDGE,
                AlarmMetrics.DispositionOutcome.APPLIED);
        metrics.recordDisposition(
                AlarmMetrics.DispositionAction.CLOSE,
                AlarmMetrics.DispositionOutcome.CONFLICT);

        assertThat(registry.get("mine.server.alarm.consume")
                .tag("outcome", "processed").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("mine.server.alarm.consume")
                .tag("outcome", "duplicate").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("mine.server.alarm.consume")
                .tag("outcome", "rejected").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("mine.server.alarm.consume")
                .tag("outcome", "requeued").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("mine.server.alarm.active")
                .tag("severity", "CRITICAL").gauge().value()).isEqualTo(7.0d);
        assertThat(registry.get("mine.server.alarm.disposition")
                .tags("action", "acknowledge", "outcome", "applied")
                .counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("mine.server.alarm.disposition")
                .tags("action", "close", "outcome", "conflict")
                .counter().count()).isEqualTo(1.0d);
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).noneMatch(tag ->
                        tag.getKey().matches("device(Id)?|rule(Key)?|alarm(Id)?")));
    }
}
