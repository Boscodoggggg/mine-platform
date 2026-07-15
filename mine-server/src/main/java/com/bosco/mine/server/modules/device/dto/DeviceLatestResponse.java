package com.bosco.mine.server.modules.device.dto;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 设备最新遥测快照。
 *
 * @param deviceId 设备标识
 * @param items 已找到的最新属性值
 * @param missingPropertyIds 当前没有数据的属性标识
 */
public record DeviceLatestResponse(
        UUID deviceId,
        List<TelemetryLatestItemResponse> items,
        List<String> missingPropertyIds
) {

    /**
     * 创建不可变设备最新值响应。
     */
    public DeviceLatestResponse {
        deviceId = Objects.requireNonNull(deviceId, "设备标识不能为空");
        items = List.copyOf(Objects.requireNonNull(items, "最新值集合不能为空"));
        missingPropertyIds = List.copyOf(Objects.requireNonNull(
                missingPropertyIds, "缺失属性集合不能为空"
        ));
    }
}
