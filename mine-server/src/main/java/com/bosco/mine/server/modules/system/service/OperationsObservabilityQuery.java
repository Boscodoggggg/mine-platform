package com.bosco.mine.server.modules.system.service;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 查询系统运行指标历史的只读端口。
 */
@FunctionalInterface
public interface OperationsObservabilityQuery {

    /**
     * 查询指定时间范围和检查项的历史指标。
     *
     * @param range 时间范围
     * @param keys  允许查询的稳定检查项键
     * @return 历史查询结果
     */
    QueryResult query(OperationsObservabilityService.TimeRange range, Set<String> keys);

    /**
     * 历史查询结果。
     *
     * @param available   查询源是否可用
     * @param generatedAt 数据生成时间
     * @param items       检查项历史
     */
    record QueryResult(boolean available, Instant generatedAt, List<ItemHistory> items) {
        public QueryResult {
            items = List.copyOf(items);
        }
    }

    /**
     * 单个检查项历史。
     *
     * @param key           稳定检查项键
     * @param category      检查项分类
     * @param statusPoints  状态采样点
     * @param latencyPoints 真实延迟采样点
     */
    record ItemHistory(
            String key,
            String category,
            List<StatusPoint> statusPoints,
            List<LatencyPoint> latencyPoints
    ) {
        public ItemHistory {
            statusPoints = List.copyOf(statusPoints);
            latencyPoints = List.copyOf(latencyPoints);
        }
    }

    /**
     * 状态采样点，值一表示健康，零表示异常。
     *
     * @param time  采样时间
     * @param value 状态数值
     */
    record StatusPoint(Instant time, int value) {
    }

    /**
     * 真实延迟采样点。
     *
     * @param time   采样时间
     * @param millis 延迟毫秒数
     */
    record LatencyPoint(Instant time, long millis) {
    }
}
