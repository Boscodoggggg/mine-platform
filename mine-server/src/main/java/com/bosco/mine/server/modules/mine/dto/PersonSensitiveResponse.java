package com.bosco.mine.server.modules.mine.dto;

import java.util.UUID;

/**
 * 仅由独立敏感权限接口返回的人员明文信息。
 *
 * @param personId 人员标识
 * @param phone 手机号码明文
 * @param certificateNumber 证件号码明文
 */
public record PersonSensitiveResponse(
        UUID personId,
        String phone,
        String certificateNumber
) {
}
