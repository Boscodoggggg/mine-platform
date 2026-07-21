package com.bosco.mine.server.modules.alarm.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 告警确认请求。
 *
 * @param note 处置说明
 * @param version 客户端读取到的乐观锁版本
 */
public record AlarmAcknowledgeRequest(
        @NotBlank @Size(max = 1000) String note,
        @Min(0) long version
) {
}
