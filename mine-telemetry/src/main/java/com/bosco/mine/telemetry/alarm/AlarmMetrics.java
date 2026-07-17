package com.bosco.mine.telemetry.alarm;

import com.bosco.mine.telemetry.alarm.outbox.TelemetryOutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Objects;

/**
 * 按固定状态暴露尚未完成可靠发布的告警 Outbox 事件数量。
 */
public final class AlarmMetrics {

    /**
     * 注册只使用固定状态标签的告警 Outbox 指标。
     *
     * @param registry 指标注册表
     * @param repository 告警 Outbox 仓储
     */
    public AlarmMetrics(MeterRegistry registry, TelemetryOutboxRepository repository) {
        Objects.requireNonNull(registry, "告警指标注册表不能为空");
        Objects.requireNonNull(repository, "告警 Outbox 仓储不能为空");
        for (String status : new String[]{"PENDING", "PUBLISHING", "FAILED", "DEAD"}) {
            Gauge.builder("mine.telemetry.alarm.outbox.events", repository,
                            value -> value.countByStatus(status))
                    .description("按固定状态统计的告警 Outbox 事件数量")
                    .tag("status", status)
                    .strongReference(true)
                    .register(registry);
        }
    }
}
