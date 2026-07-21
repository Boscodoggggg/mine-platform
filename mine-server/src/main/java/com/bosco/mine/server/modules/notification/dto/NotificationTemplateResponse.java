package com.bosco.mine.server.modules.notification.dto;

import com.bosco.mine.server.modules.notification.adapter.NotificationChannelType;
import com.bosco.mine.server.modules.notification.model.NotificationEventType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 通知模板响应。
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
 * @param allowedVariables 有序变量白名单
 * @param status 启用或禁用状态
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 * @param version 乐观锁版本号
 */
public record NotificationTemplateResponse(
        UUID id,
        String templateCode,
        String name,
        NotificationChannelType channelType,
        NotificationEventType eventType,
        String locale,
        String subjectTemplate,
        String bodyTemplate,
        String providerTemplateCode,
        List<String> allowedVariables,
        String status,
        Instant createdAt,
        Instant updatedAt,
        long version
) {
}
