package com.bosco.mine.telemetry.consumer;

import com.bosco.mine.telemetry.storage.TelemetryBatchWriter;

/**
 * 以尽力而为语义接收由持久化设备顺序状态确认的遥测序列缺口。
 * 该边界只适用于监控指标，不提供可靠事件投递保证；可靠业务事件必须使用事务 Outbox。
 */
@FunctionalInterface
public interface TelemetrySequenceGapObserver {

    /**
     * 观察一个唯一遥测事件形成的序列缺口。实现异常会被消费端隔离，不能阻塞遥测主链路。
     *
     * @param gap 已确认的设备序列缺口
     */
    void observe(TelemetryBatchWriter.SequenceGap gap);
}
