package com.bosco.mine.server.modules.device.dto;

import com.bosco.mine.model.message.UnifiedDeviceMessage.Quality;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 单个原始遥测点或聚合时间桶。
 *
 * @param occurredAt 原始发生时间或聚合桶起始时间
 * @param receivedAt 平台接收时间，聚合结果取桶内最晚接收时间
 * @param value 原始值或聚合值
 * @param quality 数据质量，聚合结果取桶内最差质量
 * @param eventId 原始事件标识，聚合结果为空
 * @param sampleCount 原始点固定为一，聚合结果为桶内样本数
 * @param source 数据来自原始遥测还是版本化长期聚合事实
 */
public record TelemetryHistoryPointResponse(
        Instant occurredAt,
        Instant receivedAt,
        double value,
        Quality quality,
        UUID eventId,
        long sampleCount,
        String source
) {

    /**
     * 创建并校验历史遥测点响应。
     */
    public TelemetryHistoryPointResponse {
        occurredAt = Objects.requireNonNull(occurredAt, "发生时间不能为空");
        receivedAt = Objects.requireNonNull(receivedAt, "接收时间不能为空");
        quality = Objects.requireNonNull(quality, "数据质量不能为空");
        source = Objects.requireNonNull(source, "历史数据来源不能为空");
        if (!source.equals("RAW") && !source.equals("ROLLUP")) {
            throw new IllegalArgumentException("历史数据来源不合法");
        }
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("遥测值必须是有限数值");
        }
        if (sampleCount < 1) {
            throw new IllegalArgumentException("样本数必须大于零");
        }
    }
}
