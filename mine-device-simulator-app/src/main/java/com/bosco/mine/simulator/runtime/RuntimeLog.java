package com.bosco.mine.simulator.runtime;

import java.time.Instant;

/**
 * 单条模拟任务运行日志。
 *
 * @param timestamp 记录时间
 * @param level 日志级别
 * @param message 日志内容
 */
public record RuntimeLog(Instant timestamp, String level, String message) {
}
