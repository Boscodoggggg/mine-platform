package com.bosco.mine.server.modules.mine.scene;

import com.bosco.mine.server.bootstrap.web.GlobalExceptionHandler;
import com.bosco.mine.server.modules.mine.scene.dto.SceneRequest;
import com.bosco.mine.server.modules.mine.scene.dto.SceneResponse;
import com.bosco.mine.server.modules.mine.scene.dto.SceneVersionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 校验场景 REST 接口分页边界、动作幂等头和乐观版本转发。
 */
class SceneControllerTest {

    private SceneQueryService queryService;
    private SceneCommandService commandService;
    private MockMvc mockMvc;
    private UUID siteId;
    private UUID sceneId;
    private UUID versionId;

    @BeforeEach
    void 创建场景接口测试环境() {
        queryService = mock(SceneQueryService.class);
        commandService = mock(SceneCommandService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new SceneController(
                                queryService,
                                commandService,
                                jakarta.validation.Validation
                                        .buildDefaultValidatorFactory().getValidator()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        siteId = UUID.randomUUID();
        sceneId = UUID.randomUUID();
        versionId = UUID.randomUUID();
    }

    @Test
    void 场景和版本列表必须声明一百的页大小上限() throws Exception {
        java.lang.reflect.Method findScenes = SceneController.class.getMethod(
                "findScenes", UUID.class, String.class, String.class, int.class, int.class);
        java.lang.reflect.Method findVersions = SceneController.class.getMethod(
                "findVersions", UUID.class, UUID.class, int.class, int.class);

        assertThat(findScenes.getParameters()[4]
                .getAnnotation(jakarta.validation.constraints.Max.class).value()).isEqualTo(100);
        assertThat(findVersions.getParameters()[3]
                .getAnnotation(jakarta.validation.constraints.Max.class).value()).isEqualTo(100);
        java.lang.reflect.Method replaceLayers = SceneController.class.getMethod(
                "replaceLayers", UUID.class, UUID.class, UUID.class, long.class, java.util.List.class);
        assertThat(replaceLayers.getParameters()[4]
                .getAnnotation(jakarta.validation.constraints.Size.class).max()).isEqualTo(256);
    }

    @Test
    void 场景和草稿修改必须显式提交乐观版本() throws Exception {
        assertThat(SceneRequest.class.getRecordComponents()[3].getType()).isEqualTo(Long.class);
        assertThat(SceneRequest.class.getRecordComponents()[3]
                .getAccessor().getAnnotation(jakarta.validation.constraints.NotNull.class))
                .isNotNull();
        assertThat(SceneVersionRequest.class.getRecordComponents()[11].getType())
                .isEqualTo(Long.class);
        assertThat(SceneVersionRequest.class.getRecordComponents()[11]
                .getAccessor().getAnnotation(jakarta.validation.constraints.NotNull.class))
                .isNotNull();

        mockMvc.perform(patch(
                        "/api/v1/mine/sites/{siteId}/scenes/{sceneId}", siteId, sceneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sceneCode\":\"main-pit\",\"name\":\"主采场\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch(
                        "/api/v1/mine/sites/{siteId}/scenes/{sceneId}/versions/{versionId}",
                        siteId, sceneId, versionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"coordinateSystemId":"%s","coordinateMode":"LOCAL_ENU",
                                 "originX":0,"originY":0,"originZ":0,
                                 "headingDegrees":0,"pitchDegrees":0,"rollDegrees":0,"scale":1,
                                 "defaultCamera":{},"fallbackViewport":{}}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 草稿坐标和图层零值字段缺失时必须拒绝请求() throws Exception {
        assertRequiredBoxedFields(
                SceneVersionRequest.class,
                "originX", "originY", "originZ", "headingDegrees",
                "pitchDegrees", "rollDegrees", "scale");
        assertRequiredBoxedFields(
                com.bosco.mine.server.modules.mine.scene.dto.SceneLayerRequest.class,
                "orderNumber", "visible", "opacity", "minimumDistance", "maximumDistance",
                "offsetX", "offsetY", "offsetZ", "headingDegrees", "pitchDegrees",
                "rollDegrees", "scale", "maxItems");

        mockMvc.perform(patch(
                        "/api/v1/mine/sites/{siteId}/scenes/{sceneId}/versions/{versionId}",
                        siteId, sceneId, versionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"coordinateSystemId":"%s","coordinateMode":"LOCAL_ENU",
                                 "originY":0,"originZ":0,
                                 "headingDegrees":0,"pitchDegrees":0,"rollDegrees":0,"scale":1,
                                 "defaultCamera":{},"fallbackViewport":{},"version":0}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put(
                        "/api/v1/mine/sites/{siteId}/scenes/{sceneId}/versions/{versionId}/layers",
                        siteId, sceneId, versionId)
                        .queryParam("version", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [{"layerCode":"coordinate-grid","orderNumber":1,
                                  "layerType":"GRID","assetId":null,
                                  "opacity":1,"minimumDistance":0,"maximumDistance":1000,
                                  "offsetX":0,"offsetY":0,"offsetZ":0,
                                  "headingDegrees":0,"pitchDegrees":0,"rollDegrees":0,
                                  "scale":1,"maxItems":0,"options":{}}]
                                """))
                .andExpect(status().isBadRequest());
    }

    /** 校验必填零值字段使用包装类型和显式非空约束。 */
    private void assertRequiredBoxedFields(Class<?> requestType, String... names) {
        for (String name : names) {
            java.lang.reflect.RecordComponent component = java.util.Arrays.stream(
                            requestType.getRecordComponents())
                    .filter(candidate -> candidate.getName().equals(name))
                    .findFirst()
                    .orElseThrow();
            assertThat(component.getType().isPrimitive()).isFalse();
            assertThat(component.getAccessor()
                    .getAnnotation(jakarta.validation.constraints.NotNull.class)).isNotNull();
        }
    }

    @Test
    void 默认归档和发布必须要求幂等头并转发乐观版本() throws Exception {
        SceneResponse response = new SceneResponse(
                sceneId, UUID.randomUUID(), siteId, "main-pit", "主采场", null,
                false, "ACTIVE", Instant.now(), Instant.now(), null, 8);
        when(commandService.setDefault(siteId, sceneId, 7, "default-key"))
                .thenReturn(response);
        when(commandService.archiveScene(siteId, sceneId, 8, "archive-key"))
                .thenReturn(response);

        String versionBody = "{\"version\":7}";
        mockMvc.perform(post(
                        "/api/v1/mine/sites/{siteId}/scenes/{sceneId}/make-default",
                        siteId, sceneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(versionBody))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(
                        "/api/v1/mine/sites/{siteId}/scenes/{sceneId}/make-default",
                        siteId, sceneId)
                        .header("Idempotency-Key", "default-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(versionBody))
                .andExpect(status().isOk());
        verify(commandService).setDefault(siteId, sceneId, 7, "default-key");

        mockMvc.perform(post(
                        "/api/v1/mine/sites/{siteId}/scenes/{sceneId}/archive",
                        siteId, sceneId)
                        .header("Idempotency-Key", "archive-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":8}"))
                .andExpect(status().isOk());
        verify(commandService).archiveScene(siteId, sceneId, 8, "archive-key");

        mockMvc.perform(post(
                        "/api/v1/mine/sites/{siteId}/scenes/{sceneId}/versions/{versionId}/publish",
                        siteId, sceneId, versionId)
                        .header("Idempotency-Key", "publish-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":3}"))
                .andExpect(status().isOk());
        verify(commandService).publish(
                eq(siteId), eq(sceneId), eq(versionId), eq(3L), eq("publish-key"));
    }

    @Test
    void 图层整体替换必须要求乐观版本且限制为二百五十六层() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/v1/mine/sites/{siteId}/scenes/{sceneId}/versions/{versionId}/layers",
                        siteId, sceneId, versionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isBadRequest());
    }
}
