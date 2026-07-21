package com.bosco.mine.server.modules.command.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.bootstrap.web.GlobalExceptionHandler;
import com.bosco.mine.server.modules.command.template.CommandTemplateRequest;
import com.bosco.mine.server.modules.command.template.CommandTemplateResponse;
import com.bosco.mine.server.modules.command.template.CommandTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 校验自定义命令模板管理接口和权限边界。
 */
class CommandTemplateManagementControllerTest {
    private static final UUID VERSION_ID = UUID.fromString("019f82c7-99a0-7472-bdc8-327702425601");

    private CommandTemplateService service;
    private MockMvc mockMvc;

    @BeforeEach
    void 创建模板管理接口测试环境() {
        service = mock(CommandTemplateService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new CommandTemplateManagementController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 应创建原始十六进制草稿并显式发布() throws Exception {
        when(service.create(any())).thenReturn(response("DRAFT", 0));
        when(service.publish(VERSION_ID, 0)).thenReturn(response("PUBLISHED", 1));

        mockMvc.perform(post("/api/v1/command-template-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payloadFormat").value("RAW_HEX"))
                .andExpect(jsonPath("$.status").value("DRAFT"));
        mockMvc.perform(post("/api/v1/command-template-definitions/{id}/publish", VERSION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        verify(service).publish(VERSION_ID, 0);
    }

    @Test
    void 列表接口应返回模板版本() throws Exception {
        when(service.list()).thenReturn(List.of(response("DRAFT", 0)));

        mockMvc.perform(get("/api/v1/command-template-definitions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].templateKey").value("HORN_RAW"));
    }

    @Test
    void 已发布模板应能复制为下一版草稿() throws Exception {
        when(service.createNextDraft(VERSION_ID)).thenReturn(response("DRAFT", 0));

        mockMvc.perform(post("/api/v1/command-template-definitions/{id}/draft", VERSION_ID))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"));

        verify(service).createNextDraft(VERSION_ID);
    }

    @Test
    void 模板管理入口必须声明查看编辑和发布权限() throws Exception {
        assertPermission("list", "command:template:view");
        assertPermission("listProducts", "command:template:view");
        assertPermission("create", "command:template:edit", CommandTemplateRequest.class);
        assertPermission("update", "command:template:edit", UUID.class, CommandTemplateRequest.class);
        assertPermission("createNextDraft", "command:template:edit", UUID.class);
        assertPermission("publish", "command:template:publish", UUID.class,
                CommandTemplateManagementController.PublishRequest.class);
    }

    private String requestJson() {
        return """
                {
                  "templateKey":"HORN_RAW",
                  "nameZh":"鸣笛原始命令",
                  "description":"固定命令字节",
                  "payloadFormat":"RAW_HEX",
                  "parameterSchema":{"type":"object","additionalProperties":false,"properties":{}},
                  "rawPayloadHex":"AA BB 01",
                  "defaultTtlSeconds":300,
                  "defaultTimeoutSeconds":30,
                  "requiresReason":true,
                  "productIds":["019f82c7-99a0-7472-bdc8-327702425602"],
                  "version":0
                }
                """;
    }

    private CommandTemplateResponse response(String status, long version) {
        Instant now = Instant.parse("2026-07-21T04:30:00Z");
        return new CommandTemplateResponse(
                UUID.randomUUID(), VERSION_ID, "HORN_RAW", 1, status,
                "鸣笛原始命令", null, "固定命令字节", "LOW", "RAW_HEX",
                Map.of("type", "object", "additionalProperties", false, "properties", Map.of()),
                "AABB01", "a".repeat(64), 300, 30, true, false,
                List.of(UUID.fromString("019f82c7-99a0-7472-bdc8-327702425602")),
                now, now, "PUBLISHED".equals(status) ? now : null, version
        );
    }

    private void assertPermission(String method, String permission, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        SaCheckPermission annotation = CommandTemplateManagementController.class
                .getMethod(method, parameterTypes)
                .getAnnotation(SaCheckPermission.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).containsExactly(permission);
    }
}
