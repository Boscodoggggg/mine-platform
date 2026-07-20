package com.bosco.mine.server.modules.mine.operation.dto;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 班次开启、关账或取消动作请求。
 *
 * @param version 客户端看到的当前乐观版本
 * @param reason 取消原因，开启和关账时为空
 */
public record ShiftActionRequest(
        @PositiveOrZero long version,
        @Size(max = 500) String reason
) {
}
