package com.bosco.mine.server.modules.command.template;

import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.modules.command.template.CommandTemplateMapper.TemplateDraft;
import com.bosco.mine.server.modules.command.template.CommandTemplateMapper.TemplateRecord;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验命令模板草稿、产品绑定和不可变发布规则。
 */
class CommandTemplateServiceTest {

    private static final UUID PRODUCT_ID = UUID.fromString("019f82c7-99a0-7472-bdc8-327702425401");
    private static final UUID OTHER_PRODUCT_ID = UUID.fromString("019f82c7-99a0-7472-bdc8-327702425404");
    private static final UUID TEMPLATE_ID = UUID.fromString("019f82c7-99a0-7472-bdc8-327702425402");
    private static final UUID VERSION_ID = UUID.fromString("019f82c7-99a0-7472-bdc8-327702425403");

    private CommandTemplateMapper mapper;
    private OperationAuditService auditService;
    private CommandTemplateService service;

    @BeforeEach
    void 创建服务() {
        mapper = mock(CommandTemplateMapper.class);
        auditService = mock(OperationAuditService.class);
        service = new CommandTemplateService(mapper, auditService, new ObjectMapper());
    }

    @Test
    void 创建原始十六进制草稿必须规范化字节并关闭回复等待() {
        when(mapper.findByTemplateKey("HORN_RAW")).thenReturn(Optional.empty());
        when(mapper.productExists(PRODUCT_ID)).thenReturn(true);
        when(mapper.insertTemplate(any(), anyString(), any())).thenReturn(1);
        when(mapper.insertVersion(any())).thenReturn(1);
        when(mapper.insertBinding(any(), any(), any(), any())).thenReturn(1);
        when(mapper.findByTemplateKey("HORN_RAW"))
                .thenReturn(Optional.empty(), Optional.of(record("DRAFT", "RAW_HEX", 0)));
        when(mapper.findProductIds(VERSION_ID)).thenReturn(List.of(PRODUCT_ID));

        CommandTemplateResponse response = service.create(request(
                "HORN_RAW", "鸣笛原始命令", "RAW_HEX", "0xAA bb 01", emptySchema(), 0));

        ArgumentCaptor<TemplateDraft> captor = ArgumentCaptor.forClass(TemplateDraft.class);
        verify(mapper).insertVersion(captor.capture());
        assertThat(captor.getValue().rawPayload()).containsExactly(
                (byte) 0xAA, (byte) 0xBB, (byte) 0x01);
        assertThat(captor.getValue().rawPayloadHex()).isEqualTo("AABB01");
        assertThat(captor.getValue().rawPayloadSha256()).hasSize(64);
        assertThat(captor.getValue().replyExpected()).isFalse();
        assertThat(response.productIds()).containsExactly(PRODUCT_ID);
    }

    @Test
    void 创建模板必须拒绝不存在的产品和危险Schema() {
        when(mapper.findByTemplateKey(anyString())).thenReturn(Optional.empty());
        when(mapper.productExists(PRODUCT_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.create(request(
                "HORN_JSON", "鸣笛命令", "MQTT_JSON", null, emptySchema(), 0)))
                .isInstanceOf(MineException.class)
                .hasMessageContaining("产品");
        verify(mapper, never()).insertTemplate(any(), anyString(), any());

        when(mapper.productExists(PRODUCT_ID)).thenReturn(true);
        Map<String, Object> unsafe = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of("duration", Map.of("type", "object"))
        );
        assertThatThrownBy(() -> service.create(request(
                "HORN_JSON", "鸣笛命令", "MQTT_JSON", null, unsafe, 0)))
                .isInstanceOf(MineException.class)
                .hasMessageContaining("参数");
    }

    @Test
    void 已发布模板不可修改且发布必须使用乐观锁() {
        when(mapper.findByVersionId(VERSION_ID))
                .thenReturn(Optional.of(record("PUBLISHED", "RAW_HEX", 3)));

        assertThatThrownBy(() -> service.update(
                VERSION_ID,
                request("HORN_RAW", "修改名称", "RAW_HEX", "AA", emptySchema(), 3)))
                .isInstanceOf(MineException.class)
                .hasMessageContaining("草稿");

        when(mapper.findByVersionId(VERSION_ID))
                .thenReturn(Optional.of(record("DRAFT", "RAW_HEX", 3)));
        when(mapper.findProductIds(VERSION_ID)).thenReturn(List.of(PRODUCT_ID));
        when(mapper.publishVersion(eq(VERSION_ID), eq(3L), isNull(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.publish(VERSION_ID, 3))
                .isInstanceOf(MineException.class)
                .hasMessageContaining("刷新");
    }

    @Test
    void 发布草稿必须先退役旧版本再原子发布当前版本() {
        TemplateRecord draft = record("DRAFT", "RAW_HEX", 2);
        when(mapper.findByVersionId(VERSION_ID)).thenReturn(Optional.of(draft));
        when(mapper.findProductIds(VERSION_ID)).thenReturn(List.of(PRODUCT_ID));
        when(mapper.publishVersion(eq(VERSION_ID), eq(2L), isNull(), any())).thenReturn(1);
        when(mapper.findByVersionId(VERSION_ID))
                .thenReturn(Optional.of(draft), Optional.of(record("PUBLISHED", "RAW_HEX", 3)));

        CommandTemplateResponse response = service.publish(VERSION_ID, 2);

        verify(mapper).retirePublished(eq(TEMPLATE_ID), eq(VERSION_ID), isNull(), any());
        verify(mapper).publishVersion(eq(VERSION_ID), eq(2L), isNull(), any());
        assertThat(response.status()).isEqualTo("PUBLISHED");
    }

    @Test
    void 修改模板审计必须分别保留变更前后产品绑定且只记录载荷摘要() {
        TemplateRecord before = record("DRAFT", "RAW_HEX", 2);
        TemplateRecord after = record("DRAFT", "RAW_HEX", 3);
        when(mapper.findByVersionId(VERSION_ID)).thenReturn(Optional.of(before), Optional.of(after));
        when(mapper.findProductIds(VERSION_ID))
                .thenReturn(List.of(PRODUCT_ID), List.of(OTHER_PRODUCT_ID));
        when(mapper.productExists(OTHER_PRODUCT_ID)).thenReturn(true);
        when(mapper.updateDraft(any(), eq(2L))).thenReturn(1);
        when(mapper.insertBinding(any(), any(), any(), any())).thenReturn(1);
        CommandTemplateRequest request = new CommandTemplateRequest(
                "HORN_RAW", "鸣笛原始命令", null, "修改后的测试模板", "RAW_HEX",
                emptySchema(), "AA", 300, 30, true, List.of(OTHER_PRODUCT_ID), 2
        );

        service.update(VERSION_ID, request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> beforeAudit = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> afterAudit = ArgumentCaptor.forClass(Map.class);
        verify(auditService).recordSuccess(
                eq("COMMAND_TEMPLATE"), eq("UPDATE_COMMAND_TEMPLATE"),
                eq("command:template:edit"), eq("COMMAND_TEMPLATE_VERSION"),
                eq(VERSION_ID), beforeAudit.capture(), afterAudit.capture()
        );
        assertThat(beforeAudit.getValue())
                .containsEntry("productIds", List.of(PRODUCT_ID))
                .containsKey("payloadSha256")
                .doesNotContainKeys("rawPayload", "rawPayloadHex");
        assertThat(afterAudit.getValue())
                .containsEntry("productIds", List.of(OTHER_PRODUCT_ID))
                .containsKey("payloadSha256")
                .doesNotContainKeys("rawPayload", "rawPayloadHex");
    }

    @Test
    void 已发布模板应能复制为保留产品绑定的下一版草稿() {
        TemplateRecord published = record("PUBLISHED", "RAW_HEX", 4);
        when(mapper.findByVersionId(VERSION_ID)).thenReturn(Optional.of(published));
        when(mapper.draftExists(TEMPLATE_ID)).thenReturn(false);
        when(mapper.nextVersionNumber(TEMPLATE_ID)).thenReturn(2);
        when(mapper.findProductIds(VERSION_ID)).thenReturn(List.of(PRODUCT_ID));
        AtomicReference<TemplateDraft> inserted = new AtomicReference<>();
        when(mapper.insertVersion(any())).thenAnswer(invocation -> {
            inserted.set(invocation.getArgument(0));
            return 1;
        });
        when(mapper.insertBinding(any(), any(), any(), any())).thenReturn(1);
        when(mapper.findByTemplateKey("HORN_RAW"))
                .thenAnswer(invocation -> Optional.of(record(
                        "DRAFT", "RAW_HEX", 0, inserted.get().versionId(), 2)));

        service.createNextDraft(VERSION_ID);

        ArgumentCaptor<TemplateDraft> draft = ArgumentCaptor.forClass(TemplateDraft.class);
        verify(mapper).insertVersion(draft.capture());
        assertThat(draft.getValue().versionNumber()).isEqualTo(2);
        assertThat(draft.getValue().rawPayload()).containsExactly((byte) 0xAA);
        assertThat(draft.getValue().rawPayloadSha256()).isEqualTo("a".repeat(64));
        verify(mapper).insertBinding(any(), eq(draft.getValue().versionId()), eq(PRODUCT_ID), any());
    }

    private CommandTemplateRequest request(
            String key,
            String name,
            String payloadFormat,
            String rawHex,
            Map<String, Object> schema,
            long version
    ) {
        return new CommandTemplateRequest(
                key, name, null, "测试模板", payloadFormat, schema, rawHex,
                300, 30, true, List.of(PRODUCT_ID), version
        );
    }

    private Map<String, Object> emptySchema() {
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of()
        );
    }

    private TemplateRecord record(String status, String payloadFormat, long version) {
        return record(status, payloadFormat, version, VERSION_ID, 1);
    }

    private TemplateRecord record(
            String status,
            String payloadFormat,
            long version,
            UUID versionId,
            int versionNumber
    ) {
        Instant now = Instant.parse("2026-07-21T00:00:00Z");
        return new TemplateRecord(
                TEMPLATE_ID, "HORN_RAW", "ACTIVE", 0,
                versionId, versionNumber, status, "鸣笛原始命令", null, "测试模板", "LOW",
                payloadFormat, "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}",
                "RAW_HEX".equals(payloadFormat) ? new byte[]{(byte) 0xAA} : null,
                "RAW_HEX".equals(payloadFormat) ? "AA" : null,
                "RAW_HEX".equals(payloadFormat) ? "a".repeat(64) : null,
                300, 30, true, !"RAW_HEX".equals(payloadFormat),
                now, now, "PUBLISHED".equals(status) ? now : null, version
        );
    }
}
