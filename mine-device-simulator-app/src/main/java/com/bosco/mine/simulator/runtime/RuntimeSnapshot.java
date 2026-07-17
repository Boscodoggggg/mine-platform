package com.bosco.mine.simulator.runtime;

import com.bosco.mine.simulator.transport.TransportState;

import java.time.Instant;
import java.util.List;

/**
 * 提供给管理页面的模拟任务运行快照。
 *
 * @param profileId 配置标识
 * @param running 周期任务是否启用
 * @param state 连接状态
 * @param sentCount 成功发送次数
 * @param failedCount 失败次数
 * @param lastSentAt 最近成功发送时间
 * @param lastError 最近一次错误
 * @param logs 最近运行日志
 */
public record RuntimeSnapshot(
        String profileId,
        boolean running,
        TransportState state,
        long sentCount,
        long failedCount,
        Instant lastSentAt,
        String lastError,
        List<RuntimeLog> logs) {
}
