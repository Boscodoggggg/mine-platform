package com.bosco.mine.server.modules.notification.service;

import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.modules.notification.adapter.NotificationChannelType;
import com.bosco.mine.server.modules.notification.dto.NotificationTemplateRequest;
import com.bosco.mine.server.modules.notification.dto.NotificationTemplateResponse;
import com.bosco.mine.server.modules.notification.model.NotificationEventType;
import com.bosco.mine.server.modules.notification.model.NotificationTemplate;
import com.bosco.mine.server.modules.notification.repository.NotificationTemplateMapper;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验通知模板变量白名单、安全语法和乐观锁规则。
 */
class NotificationTemplateServiceTest {

    private NotificationTemplateMapper mapper;
    private OperationAuditService auditService;
    private NotificationTemplateService service;

    @BeforeEach
    void 创建通知模板测试环境() {
        mapper = mock(NotificationTemplateMapper.class);
        auditService = mock(OperationAuditService.class);
        service = new NotificationTemplateService(mapper, auditService, new ObjectMapper());
    }

    @Test
    void 创建模板必须保存排序后的固定变量白名单() {
        when(mapper.findByCode("critical-alarm-email")).thenReturn(Optional.empty());
        when(mapper.insert(any(), any())).thenReturn(1);

        NotificationTemplateResponse response = service.create(request(
                "{{recipient.displayName}}，{{mine.name}}发生{{alarm.severity}}告警",
                Set.of("mine.name", "alarm.severity", "recipient.displayName"), 0));

        ArgumentCaptor<NotificationTemplate> template =
                ArgumentCaptor.forClass(NotificationTemplate.class);
        verify(mapper).insert(template.capture(), any());
        assertThat(template.getValue().allowedVariablesJson())
                .isEqualTo("[\"alarm.severity\",\"mine.name\",\"recipient.displayName\"]");
        assertThat(response.allowedVariables())
                .containsExactly("alarm.severity", "mine.name", "recipient.displayName");
    }

    @Test
    void 模板引用未声明变量必须被拒绝() {
        when(mapper.findByCode("critical-alarm-email")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request(
                "矿区 {{mine.name}} 发生 {{alarm.severity}} 告警",
                Set.of("mine.name"), 0)))
                .isInstanceOf(MineException.class)
                .satisfies(exception -> assertThat(((MineException) exception).details())
                        .containsEntry("reason", "VARIABLE_NOT_ALLOWED"));
    }

    @Test
    void 模板必须拒绝非平台变量和任何表达式语法() {
        when(mapper.findByCode("critical-alarm-email")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request(
                "告警 {{system.runtime.exec}}",
                Set.of("system.runtime.exec"), 0)))
                .isInstanceOf(MineException.class)
                .satisfies(exception -> assertThat(((MineException) exception).details())
                        .containsEntry("reason", "VARIABLE_UNSUPPORTED"));
        assertThatThrownBy(() -> service.create(request(
                "告警 ${T(java.lang.Runtime).getRuntime()}", Set.of(), 0)))
                .isInstanceOf(MineException.class)
                .satisfies(exception -> assertThat(((MineException) exception).details())
                        .containsEntry("reason", "EXPRESSION_NOT_ALLOWED"));
        assertThatThrownBy(() -> service.create(request(
                "告警 {{alarm.severity | upper}}",
                Set.of("alarm.severity"), 0)))
                .isInstanceOf(MineException.class)
                .satisfies(exception -> assertThat(((MineException) exception).details())
                        .containsEntry("reason", "PLACEHOLDER_INVALID"));
    }

    @Test
    void 邮件标题必须阻止换行且启用短信语音必须提供供应商模板编码() {
        when(mapper.findByCode(any())).thenReturn(Optional.empty());
        NotificationTemplateRequest headerInjection = new NotificationTemplateRequest(
                "mail-header", "邮件标题", NotificationChannelType.EMAIL,
                NotificationEventType.ALARM_RAISED, "zh-CN",
                "严重告警\r\nBcc: attacker@example.com", "告警正文", null,
                Set.of(), "ENABLED", 0);
        NotificationTemplateRequest smsWithoutCode = new NotificationTemplateRequest(
                "critical-sms", "严重短信", NotificationChannelType.SMS,
                NotificationEventType.ALARM_RAISED, "zh-CN",
                null, "告警正文", null, Set.of(), "ENABLED", 0);

        assertThatThrownBy(() -> service.create(headerInjection))
                .isInstanceOf(MineException.class)
                .satisfies(exception -> assertThat(((MineException) exception).details())
                        .containsEntry("reason", "SUBJECT_INVALID"));
        assertThatThrownBy(() -> service.create(smsWithoutCode))
                .isInstanceOf(MineException.class)
                .satisfies(exception -> assertThat(((MineException) exception).details())
                        .containsEntry("reason", "PROVIDER_TEMPLATE_CODE_REQUIRED"));
    }

    @Test
    void 模板编码必须唯一且修改必须使用乐观锁() {
        UUID templateId = UUID.randomUUID();
        when(mapper.findByCode("critical-alarm-email"))
                .thenReturn(Optional.of(template(templateId, 2)));

        assertThatThrownBy(() -> service.create(request("告警正文", Set.of(), 0)))
                .isInstanceOf(MineException.class)
                .hasMessageContaining("编码");
        when(mapper.findById(templateId)).thenReturn(Optional.of(template(templateId, 3)));
        assertThatThrownBy(() -> service.update(
                templateId, request("告警正文", Set.of(), 2)))
                .isInstanceOf(MineException.class)
                .hasMessageContaining("刷新");
    }

    @Test
    void 合法修改必须增加版本并记录无模板正文审计() {
        UUID templateId = UUID.randomUUID();
        NotificationTemplate existing = template(templateId, 3);
        when(mapper.findById(templateId)).thenReturn(Optional.of(existing));
        when(mapper.update(any(), eq(3L), any())).thenReturn(1);

        NotificationTemplateResponse response = service.update(
                templateId,
                request("{{mine.name}} 告警已恢复", Set.of("mine.name"), 3));

        assertThat(response.version()).isEqualTo(4);
        ArgumentCaptor<java.util.Map<String, Object>> after =
                ArgumentCaptor.forClass(java.util.Map.class);
        verify(auditService).recordSuccess(
                eq("NOTIFICATION"), eq("UPDATE_NOTIFICATION_TEMPLATE"),
                eq("notification:template:edit"), eq("NOTIFICATION_TEMPLATE"),
                eq(templateId), any(), after.capture());
        assertThat(after.getValue().toString()).doesNotContain("告警已恢复");
    }

    private NotificationTemplateRequest request(
            String body,
            Set<String> variables,
            long version
    ) {
        return new NotificationTemplateRequest(
                "critical-alarm-email", "严重告警邮件", NotificationChannelType.EMAIL,
                NotificationEventType.ALARM_RAISED, "zh-CN", "严重告警",
                body, null, variables, "ENABLED", version);
    }

    private NotificationTemplate template(UUID id, long version) {
        Instant now = Instant.parse("2026-07-21T00:00:00Z");
        return new NotificationTemplate(
                id, "critical-alarm-email", "严重告警邮件",
                NotificationChannelType.EMAIL, NotificationEventType.ALARM_RAISED,
                "zh-CN", "严重告警", "告警正文", null, "[]",
                "ENABLED", now, now, version);
    }
}
