package com.bosco.mine.server.modules.alarm.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 已恢复告警关闭请求。
 *
 * @param note 关闭说明
 * @param version 客户端读取到的乐观锁版本
 */
public record AlarmCloseRequest(
        @NotBlank @Size(max = 1000) String note,
        @Min(0) long version
) {
}
