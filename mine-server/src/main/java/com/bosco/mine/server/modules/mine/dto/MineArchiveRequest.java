package com.bosco.mine.server.modules.mine.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 归档组织或矿区请求。
 *
 * @param version 期望乐观锁版本
 */
public record MineArchiveRequest(@NotNull @Min(0) Long version) {
}
