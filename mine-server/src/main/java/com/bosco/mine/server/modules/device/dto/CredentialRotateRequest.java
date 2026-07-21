package com.bosco.mine.server.modules.device.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 设备凭据轮换请求。
 *
 * @param version 当前凭据版本
 */
public record CredentialRotateRequest(@NotNull @Min(0) Long version) {
}
