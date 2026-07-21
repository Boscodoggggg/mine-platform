package com.bosco.mine.telemetry.alarm;

import com.bosco.mine.telemetry.alarm.outbox.TelemetryOutboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 校验告警 Outbox 积压指标不携带业务标识标签。
 */
class AlarmMetricsTest {

    @Test
    void 应暴露当前待发布积压且不使用高基数标签() {
        TelemetryOutboxRepository repository = mock(TelemetryOutboxRepository.class);
        when(repository.countByStatus("PENDING")).thenReturn(23L);
        when(repository.countByStatus("PUBLISHING")).thenReturn(2L);
        when(repository.countByStatus("FAILED")).thenReturn(3L);
        when(repository.countByStatus("DEAD")).thenReturn(1L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new AlarmMetrics(registry, repository);

        assertThat(registry.get("mine.telemetry.alarm.outbox.events")
                .tag("status", "PENDING").gauge().value())
                .isEqualTo(23.0d);
        assertThat(registry.get("mine.telemetry.alarm.outbox.events")
                .tag("status", "FAILED").gauge().value()).isEqualTo(3.0d);
        assertThat(registry.get("mine.telemetry.alarm.outbox.events")
                .tag("status", "DEAD").gauge().value()).isEqualTo(1.0d);
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).noneMatch(tag ->
                        tag.getKey().matches("device(Id)?|rule(Key)?|alarm(Id)?|event(Id)?")));
    }
}
