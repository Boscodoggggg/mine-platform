package com.bosco.mine.server.modules.device.dto;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 设备单属性历史遥测分页响应。
 *
 * @param deviceId 设备标识
 * @param propertyId 属性标识
 * @param aggregation 聚合方式
 * @param bucket 聚合时间桶，原始查询为空
 * @param watermark 分页数据集的全局摄取水位上限
 * @param items 当前页数据
 * @param page 页码
 * @param size 每页数量
 * @param total 原始点总数或聚合桶总数
 */
public record TelemetryHistoryResponse(
        UUID deviceId,
        String propertyId,
        String aggregation,
        Duration bucket,
        long watermark,
        List<TelemetryHistoryPointResponse> items,
        int page,
        int size,
        long total
) {

    /**
     * 创建并校验历史遥测分页响应。
     */
    public TelemetryHistoryResponse {
        deviceId = Objects.requireNonNull(deviceId, "设备标识不能为空");
        propertyId = Objects.requireNonNull(propertyId, "属性标识不能为空");
        aggregation = Objects.requireNonNull(aggregation, "聚合方式不能为空");
        items = List.copyOf(Objects.requireNonNull(items, "历史数据不能为空"));
        if (watermark < 0 || page < 1 || size < 1 || total < 0) {
            throw new IllegalArgumentException("历史分页参数不合法");
        }
    }
}
