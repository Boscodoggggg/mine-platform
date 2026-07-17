package com.bosco.mine.server.modules.alarm.dto;

import com.bosco.mine.server.modules.alarm.domain.AlarmStatus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 告警中心列表和详情共用的完整告警周期响应。
 *
 * @param id 告警周期标识
 * @param deviceId 设备标识
 * @param deviceCode 设备稳定编码
 * @param deviceName 设备名称
 * @param productKey 产品编码快照
 * @param ruleKey 规则编码
 * @param ruleName 规则名称快照
 * @param sourceType 来源类型
 * @param severity 告警等级
 * @param propertyId 数值属性编码
 * @param status 推导状态
 * @param policyRuntimeRevision 策略运行修订号
 * @param policySnapshotId 策略快照标识
 * @param policyContentHash 策略内容摘要
 * @param raisedAt 触发时间
 * @param raisedEvidence 触发证据
 * @param recoveredAt 恢复时间
 * @param recoveredEvidence 恢复证据
 * @param recoveryReason 恢复原因
 * @param acknowledgedAt 确认时间
 * @param acknowledgedBy 确认人标识
 * @param acknowledgementNote 确认说明
 * @param closedAt 关闭时间
 * @param closedBy 关闭人标识
 * @param closeNote 关闭说明
 * @param version 乐观锁版本
 */
public record AlarmEventResponse(
        UUID id,
        UUID deviceId,
        String deviceCode,
        String deviceName,
        String productKey,
        String ruleKey,
        String ruleName,
        String sourceType,
        String severity,
        String propertyId,
        AlarmStatus status,
        long policyRuntimeRevision,
        UUID policySnapshotId,
        String policyContentHash,
        Instant raisedAt,
        Map<String, Object> raisedEvidence,
        Instant recoveredAt,
        Map<String, Object> recoveredEvidence,
        String recoveryReason,
        Instant acknowledgedAt,
        UUID acknowledgedBy,
        String acknowledgementNote,
        Instant closedAt,
        UUID closedBy,
        String closeNote,
        long version
) {
}
