package com.bosco.mine.server.modules.notification.dto;

import com.bosco.mine.server.modules.notification.adapter.NotificationChannelType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 通知渠道创建或修改请求。
 *
 * @param channelCode 稳定小写渠道编码
 * @param name 渠道名称
 * @param channelType 渠道类型
 * @param provider 平台内置 Provider 编码
 * @param status 启用或禁用状态
 * @param configuration 非敏感 Provider 配置
 * @param secrets 只写 Provider 凭据，更新时只覆盖出现的字段
 * @param connectTimeoutMs 连接超时毫秒数
 * @param readTimeoutMs 读取超时毫秒数
 * @param version 期望乐观锁版本号
 */
public record NotificationChannelRequest(
        @NotBlank @Pattern(regexp = "^[a-z][a-z0-9-]{1,63}$") String channelCode,
        @NotBlank @Size(max = 128) String name,
        @NotNull NotificationChannelType channelType,
        @NotBlank @Pattern(regexp = "^[a-z][a-z0-9-]{1,63}$") String provider,
        @NotBlank @Pattern(regexp = "ENABLED|DISABLED") String status,
        @NotNull @Size(max = 128) Map<String, Object> configuration,
        @NotNull @Size(max = 128) Map<String, String> secrets,
        @Min(100) @Max(30000) int connectTimeoutMs,
        @Min(100) @Max(60000) int readTimeoutMs,
        @Min(0) long version
) {
}
