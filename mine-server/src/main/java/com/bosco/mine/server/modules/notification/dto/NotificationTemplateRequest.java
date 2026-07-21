package com.bosco.mine.server.modules.notification.dto;

import com.bosco.mine.server.modules.notification.adapter.NotificationChannelType;
import com.bosco.mine.server.modules.notification.model.NotificationEventType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Set;

/**
 * 通知模板创建或修改请求。
 *
 * @param templateCode 稳定小写模板编码
 * @param name 模板名称
 * @param channelType 渠道类型
 * @param eventType 业务事件类型
 * @param locale 语言区域
 * @param subjectTemplate 标题模板
 * @param bodyTemplate 正文模板
 * @param providerTemplateCode 供应商模板编码
 * @param allowedVariables 允许变量白名单
 * @param status 启用或禁用状态
 * @param version 期望乐观锁版本号
 */
public record NotificationTemplateRequest(
        @NotBlank @Pattern(regexp = "^[a-z][a-z0-9-]{1,63}$") String templateCode,
        @NotBlank @Size(max = 128) String name,
        @NotNull NotificationChannelType channelType,
        @NotNull NotificationEventType eventType,
        @NotBlank @Pattern(regexp = "zh-CN|en-US") String locale,
        @Size(max = 1000) String subjectTemplate,
        @NotBlank @Size(max = 16384) String bodyTemplate,
        @Size(max = 128) String providerTemplateCode,
        @NotNull @Size(max = 64) Set<String> allowedVariables,
        @NotBlank @Pattern(regexp = "ENABLED|DISABLED") String status,
        @Min(0) long version
) {
    /** 返回不包含模板正文的安全日志摘要。 */
    @Override
    public String toString() {
        return "NotificationTemplateRequest[templateCode=" + templateCode
                + ", channelType=" + channelType
                + ", eventType=" + eventType
                + ", locale=" + locale
                + ", variableCount=" + (allowedVariables == null ? 0 : allowedVariables.size())
                + ", status=" + status
                + ", version=" + version + "]";
    }
}
