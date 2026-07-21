package com.bosco.mine.server.modules.notification.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 通知升级策略根配置和当前草稿的整体写入请求。
 *
 * @param policyCode 稳定小写策略编码
 * @param name 策略名称
 * @param description 策略说明
 * @param priority 匹配优先级
 * @param matchConditions 矿区和规则匹配条件
 * @param severityFilter 告警等级过滤集合
 * @param stopOnAck 告警确认后是否停止后续升级
 * @param notifyOnRecovery 告警恢复时是否通知
 * @param steps 有序升级步骤
 * @param version 期望聚合乐观锁版本号
 */
public record NotificationPolicyRequest(
        @NotBlank @Pattern(regexp = "^[a-z][a-z0-9-]{1,63}$") String policyCode,
        @NotBlank @Size(max = 128) String name,
        @Size(max = 500) String description,
        @Min(0) @Max(1000) int priority,
        @NotNull @Valid MatchConditions matchConditions,
        @NotEmpty @Size(max = 3) Set<@Pattern(regexp = "INFO|WARNING|CRITICAL") String> severityFilter,
        boolean stopOnAck,
        boolean notifyOnRecovery,
        @NotEmpty @Size(max = 20) List<@Valid Step> steps,
        @Min(0) long version
) {
    /**
     * 策略的矿区和告警规则匹配范围，空集合表示对应维度不限制。
     *
     * @param mineIds 矿区标识集合
     * @param ruleKeys 稳定告警规则编码集合
     */
    public record MatchConditions(
            @NotNull @Size(max = 100) Set<UUID> mineIds,
            @NotNull @Size(max = 100)
            Set<@Pattern(regexp = "^[a-z][a-z0-9._-]{0,127}$") String> ruleKeys
    ) {
    }

    /**
     * 单个通知升级步骤请求。
     *
     * @param stepOrder 从零开始的步骤顺序
     * @param delaySeconds 相对告警产生时间的延迟秒数
     * @param channelId 通知渠道标识
     * @param recipientGroupId 接收组标识
     * @param templateId 告警触发模板标识
     * @param recoveryTemplateId 告警恢复模板标识
     * @param ticketAction 事件单动作
     * @param ticketPriority 事件单优先级
     */
    public record Step(
            @Min(0) int stepOrder,
            @Min(0) @Max(604800) int delaySeconds,
            @NotNull UUID channelId,
            @NotNull UUID recipientGroupId,
            @NotNull UUID templateId,
            UUID recoveryTemplateId,
            @NotBlank @Pattern(regexp = "NONE|CREATE|ASSIGN|ESCALATE") String ticketAction,
            @Min(1) @Max(100) Integer ticketPriority
    ) {
    }
}
