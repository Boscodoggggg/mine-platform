package com.bosco.mine.telemetry.consumer;

import com.bosco.mine.telemetry.storage.TelemetryBatchWriter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Objects;

/**
 * 将遥测设备序列缺口记录为低基数 Micrometer 指标。
 */
public final class TelemetrySequenceGapMetrics implements TelemetrySequenceGapObserver {

    private final Counter gapEvents;
    private final Counter missingSequences;

    /**
     * 创建遥测序列缺口指标。
     *
     * @param meterRegistry Micrometer 指标注册表
     */
    public TelemetrySequenceGapMetrics(MeterRegistry meterRegistry) {
        Objects.requireNonNull(meterRegistry, "指标注册表不能为空");
        this.gapEvents = Counter.builder("mine.telemetry.sequence.gap.events")
                .description("持久化顺序状态确认的遥测序列缺口事件数")
                .register(meterRegistry);
        this.missingSequences = Counter.builder("mine.telemetry.sequence.gap.missing")
                .description("持久化顺序状态确认的缺失遥测序号总数")
                .register(meterRegistry);
    }

    /**
     * 记录一次缺口事件及其缺失序号数量。
     *
     * @param gap 已确认的设备序列缺口
     */
    @Override
    public void observe(TelemetryBatchWriter.SequenceGap gap) {
        Objects.requireNonNull(gap, "序列缺口不能为空");
        gapEvents.increment();
        missingSequences.increment(gap.missingCount());
    }
}
