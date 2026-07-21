package com.bosco.mine.server.modules.notification.model;

import com.bosco.mine.server.modules.notification.adapter.NotificationChannelType;

import java.time.Instant;
import java.util.UUID;

/**
 * 数据库中的安全通知模板配置快照。
 *
 * @param id 模板标识
 * @param templateCode 稳定模板编码
 * @param name 模板名称
 * @param channelType 渠道类型
 * @param eventType 业务事件类型
 * @param locale 语言区域
 * @param subjectTemplate 标题模板
 * @param bodyTemplate 正文模板
 * @param providerTemplateCode 供应商模板编码
 * @param allowedVariablesJson 允许变量白名单 JSON
 * @param status 启用或禁用状态
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 * @param version 乐观锁版本号
 */
public record NotificationTemplate(
        UUID id,
        String templateCode,
        String name,
        NotificationChannelType channelType,
        NotificationEventType eventType,
        String locale,
        String subjectTemplate,
        String bodyTemplate,
        String providerTemplateCode,
        String allowedVariablesJson,
        String status,
        Instant createdAt,
        Instant updatedAt,
        long version
) {
    /** 返回不包含标题和正文内容的安全日志摘要。 */
    @Override
    public String toString() {
        return "NotificationTemplate[id=" + id
                + ", templateCode=" + templateCode
                + ", channelType=" + channelType
                + ", eventType=" + eventType
                + ", locale=" + locale
                + ", status=" + status
                + ", version=" + version + "]";
    }
}
