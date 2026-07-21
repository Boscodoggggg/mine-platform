package com.bosco.mine.server.modules.device.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 设备接入配置响应，不包含任何明文凭据。
 *
 * @param id                       配置标识
 * @param deviceId                 设备标识
 * @param connectionMode           接入方式
 * @param host                     平台接入端点或目标设备主机
 * @param port                     接入端口
 * @param tlsEnabled               是否校验 TLS
 * @param tlsServerName            TLS SNI 与主机名校验名称
 * @param connectTimeoutMillis     单阶段连接超时
 * @param keepAliveSeconds         建议保活间隔
 * @param offlineThresholdSeconds  离线判定阈值
 * @param lastTestStatus           最近诊断状态
 * @param lastTestAt               最近诊断时间
 * @param lastTestDurationMillis   最近诊断耗时
 * @param lastTestMessage          最近诊断结论
 * @param lastTestStages           最近诊断阶段
 * @param createdAt                创建时间
 * @param updatedAt                更新时间
 * @param version                  乐观锁版本
 */
public record DeviceConnectionProfileResponse(
        UUID id,
        UUID deviceId,
        String connectionMode,
        String host,
        int port,
        boolean tlsEnabled,
        String tlsServerName,
        int connectTimeoutMillis,
        int keepAliveSeconds,
        int offlineThresholdSeconds,
        String lastTestStatus,
        Instant lastTestAt,
        Long lastTestDurationMillis,
        String lastTestMessage,
        List<DeviceConnectionTestResponse.Stage> lastTestStages,
        Instant createdAt,
        Instant updatedAt,
        long version
) {
}
