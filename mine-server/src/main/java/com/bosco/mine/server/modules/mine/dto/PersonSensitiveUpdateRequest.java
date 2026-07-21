package com.bosco.mine.server.modules.mine.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 使用独立权限更新人员敏感身份信息。
 *
 * @param phone 手机号码明文，仅用于本次写入加密
 * @param certificateNumber 证件号码明文，仅用于本次写入加密
 * @param version 人员主档乐观锁版本
 */
public record PersonSensitiveUpdateRequest(
        @NotBlank @Size(max = 32) String phone,
        @NotBlank @Size(max = 64) String certificateNumber,
        @PositiveOrZero long version
) {
}
