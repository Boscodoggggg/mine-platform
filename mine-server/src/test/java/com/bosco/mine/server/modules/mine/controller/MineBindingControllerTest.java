package com.bosco.mine.server.modules.mine.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.bootstrap.web.GlobalExceptionHandler;
import com.bosco.mine.server.modules.mine.dto.BindingPreviewResponse;
import com.bosco.mine.server.modules.mine.dto.BindingSwitchRequest;
import com.bosco.mine.server.modules.mine.service.MineBindingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 校验矿区换绑预览、管理员幂等键和权限注解的 HTTP 契约。
 */
class MineBindingControllerTest {

    private MineBindingService service;
    private MockMvc mockMvc;
    private UUID siteId;

    @BeforeEach
    void 创建换绑接口测试环境() {
        service = mock(MineBindingService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new MineBindingController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        siteId = UUID.randomUUID();
    }

    @Test
    void 预览和执行应使用同一结构化响应且透传管理员幂等键() throws Exception {
        UUID resourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        BindingPreviewResponse preview = response(null, false, resourceId, targetId);
        BindingPreviewResponse switched = response(operationId, true, resourceId, targetId);
        when(service.preview(eq(siteId), any())).thenReturn(preview);
        when(service.switchBinding(eq(siteId), any(), eq("binding-operation-001")))
                .thenReturn(switched);
        String body = requestBody(resourceId, targetId);

        mockMvc.perform(post("/api/v1/mine/sites/{siteId}/bindings/preview", siteId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executed").value(false))
                .andExpect(jsonPath("$.proposedBinding.resourceId")
                        .value(resourceId.toString()));
        mockMvc.perform(post("/api/v1/mine/sites/{siteId}/bindings/switch", siteId)
                        .header("Idempotency-Key", "binding-operation-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationId").value(operationId.toString()))
                .andExpect(jsonPath("$.executed").value(true));

        verify(service).preview(eq(siteId), any(BindingSwitchRequest.class));
        verify(service).switchBinding(
                eq(siteId), any(BindingSwitchRequest.class), eq("binding-operation-001"));
    }

    @Test
    void 执行缺少幂等键或请求字段非法时必须返回参数错误() throws Exception {
        UUID resourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/mine/sites/{siteId}/bindings/switch", siteId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(resourceId, targetId)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/mine/sites/{siteId}/bindings/preview", siteId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bindingType":"UNKNOWN",
                                  "resourceId":"%s",
                                  "targetId":"%s",
                                  "effectiveAt":"2026-07-20T08:00:00Z",
                                  "reason":""
                                }
                                """.formatted(resourceId, targetId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 预览和执行入口都必须声明换绑权限() throws NoSuchMethodException {
        assertPermission("preview", UUID.class, BindingSwitchRequest.class);
        assertPermission("switchBinding", UUID.class, String.class, BindingSwitchRequest.class);
    }

    private BindingPreviewResponse response(
            UUID operationId,
            boolean executed,
            UUID resourceId,
            UUID targetId
    ) {
        Instant effectiveAt = Instant.parse("2026-07-20T08:00:00Z");
        return new BindingPreviewResponse(
                operationId, executed, true, "VEHICLE_DEVICE", null,
                new BindingPreviewResponse.BindingRelation(
                        executed ? UUID.randomUUID() : null,
                        "VEHICLE_DEVICE", resourceId, targetId,
                        "PRIMARY", effectiveAt, null),
                new BindingPreviewResponse.QualificationAssessment(false, true, null),
                List.of(new BindingPreviewResponse.AffectedObject(
                        "DEVICE", resourceId, "RESOURCE")),
                List.of(), executed ? UUID.randomUUID() : null);
    }

    private String requestBody(UUID resourceId, UUID targetId) {
        return """
                {
                  "bindingType":"VEHICLE_DEVICE",
                  "resourceId":"%s",
                  "targetId":"%s",
                  "role":"PRIMARY",
                  "effectiveAt":"2026-07-20T08:00:00Z",
                  "reason":"更换主定位设备"
                }
                """.formatted(resourceId, targetId);
    }

    private void assertPermission(String methodName, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        SaCheckPermission annotation = MineBindingController.class
                .getMethod(methodName, parameterTypes)
                .getAnnotation(SaCheckPermission.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).containsExactly("mine:binding:switch");
    }
}
