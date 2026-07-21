package com.bosco.mine.server.modules.notification.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 通知升级策略发布请求。
 *
 * @param versionId 待发布草稿版本标识
 * @param publicationReason 发布原因
 * @param version 期望策略聚合版本号
 */
public record NotificationPolicyPublishRequest(
        @NotNull UUID versionId,
        @NotBlank @Size(max = 500) String publicationReason,
        @Min(0) long version
) {
}
