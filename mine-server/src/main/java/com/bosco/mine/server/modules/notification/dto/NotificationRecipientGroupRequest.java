package com.bosco.mine.server.modules.notification.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 通知接收组创建或修改请求。
 *
 * @param groupCode 稳定小写接收组编码
 * @param name 接收组名称
 * @param description 接收组说明
 * @param status 启用或禁用状态
 * @param version 期望乐观锁版本号
 */
public record NotificationRecipientGroupRequest(
        @NotBlank @Pattern(regexp = "^[a-z][a-z0-9-]{1,63}$") String groupCode,
        @NotBlank @Size(max = 128) String name,
        @Size(max = 500) String description,
        @NotBlank @Pattern(regexp = "ENABLED|DISABLED") String status,
        @Min(0) long version
) {
}
