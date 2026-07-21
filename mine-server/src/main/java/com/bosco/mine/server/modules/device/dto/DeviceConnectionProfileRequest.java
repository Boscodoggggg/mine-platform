package com.bosco.mine.server.modules.device.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 设备接入配置保存请求，不允许携带设备密码或令牌。
 *
 * @param connectionMode          接入方式
 * @param host                    平台接入端点或目标设备主机
 * @param port                    接入端口
 * @param tlsEnabled              是否校验 TLS
 * @param tlsServerName           TLS SNI 与主机名校验名称
 * @param connectTimeoutMillis    单阶段连接超时
 * @param keepAliveSeconds        建议保活间隔
 * @param offlineThresholdSeconds 离线判定阈值
 * @param version                 乐观锁版本
 */
public record DeviceConnectionProfileRequest(
        @NotBlank
        @Pattern(regexp = "DEVICE_TO_PLATFORM_MQTT|DEVICE_TO_PLATFORM_TCP|PLATFORM_TO_DEVICE_TCP")
        String connectionMode,
        @NotBlank @Size(max = 253)
        @Pattern(regexp = "[A-Za-z0-9](?:[A-Za-z0-9.:-]{0,251}[A-Za-z0-9])?") String host,
        @Min(1) @Max(65535) int port,
        @NotNull Boolean tlsEnabled,
        @Size(max = 253)
        @Pattern(regexp = "[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?") String tlsServerName,
        @Min(250) @Max(30000) int connectTimeoutMillis,
        @Min(0) @Max(86400) int keepAliveSeconds,
        @Min(1) @Max(604800) int offlineThresholdSeconds,
        @Min(0) long version
) {
}
