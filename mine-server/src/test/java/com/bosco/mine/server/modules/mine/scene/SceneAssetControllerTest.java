package com.bosco.mine.server.modules.mine.scene;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.bootstrap.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 校验三维资源上传、条件读取、单范围读取和权限注解的 HTTP 契约。
 */
class SceneAssetControllerTest {

    @TempDir
    private Path temporaryDirectory;

    private SceneAssetService service;
    private MockMvc mockMvc;
    private UUID siteId;
    private UUID assetId;
    private Path file;

    @BeforeEach
    void 创建资源接口测试环境() throws Exception {
        service = mock(SceneAssetService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new SceneAssetController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        siteId = UUID.randomUUID();
        assetId = UUID.randomUUID();
        file = temporaryDirectory.resolve("model.glb");
        Files.writeString(file, "0123456789", StandardCharsets.UTF_8);
        when(service.openFile(siteId, assetId, "model.glb"))
                .thenReturn(new SceneAssetService.SceneAssetFileContent(
                        file, "model/gltf-binary", "sha256:file", 10));
    }

    @Test
    void 文件读取应提供缓存安全头并支持条件请求() throws Exception {
        String url = "/api/v1/mine/sites/{siteId}/scene-assets/{assetId}/files/model.glb";
        mockMvc.perform(get(url, siteId, assetId))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"sha256:file\""))
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().contentType("model/gltf-binary"))
                .andExpect(content().bytes("0123456789".getBytes(StandardCharsets.UTF_8)));

        mockMvc.perform(get(url, siteId, assetId)
                        .header("If-None-Match", "\"sha256:file\""))
                .andExpect(status().isNotModified())
                .andExpect(content().bytes(new byte[0]));
    }

    @Test
    void 文件读取应只接受一个合法字节范围() throws Exception {
        String url = "/api/v1/mine/sites/{siteId}/scene-assets/{assetId}/files/model.glb";
        mockMvc.perform(get(url, siteId, assetId).header("Range", "bytes=2-5"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 2-5/10"))
                .andExpect(content().bytes("2345".getBytes(StandardCharsets.UTF_8)));
        mockMvc.perform(get(url, siteId, assetId).header("Range", "bytes=0-1,4-5"))
                .andExpect(status().isRequestedRangeNotSatisfiable());
        mockMvc.perform(get(url, siteId, assetId).header("Range", "bytes=20-30"))
                .andExpect(status().isRequestedRangeNotSatisfiable());
    }

    @Test
    void 未授权和未知清单路径应返回稳定错误状态() throws Exception {
        UUID forbiddenAsset = UUID.randomUUID();
        UUID missingAsset = UUID.randomUUID();
        when(service.openFile(siteId, forbiddenAsset, "model.glb"))
                .thenThrow(new MineException(
                        new ErrorCode("MINE-SCOPE-403-002", "无权访问该组织或矿区"), Map.of()));
        when(service.openFile(siteId, missingAsset, "model.glb"))
                .thenThrow(new MineException(
                        new ErrorCode("MINE-SCENE-ASSET-404-002", "资源文件不存在"), Map.of()));

        mockMvc.perform(get(
                        "/api/v1/mine/sites/{siteId}/scene-assets/{assetId}/files/model.glb",
                        siteId, forbiddenAsset))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(
                        "/api/v1/mine/sites/{siteId}/scene-assets/{assetId}/files/model.glb",
                        siteId, missingAsset))
                .andExpect(status().isNotFound());
    }

    @Test
    void 上传接口应使用多段表单并声明资源上传权限() throws Exception {
        MockMultipartFile upload = new MockMultipartFile(
                "file", "pit.glb", "model/gltf-binary", new byte[]{1, 2, 3});
        when(service.upload(eq(siteId), any(), eq("pit.glb"), eq("model/gltf-binary")))
                .thenThrow(new MineException(
                        new ErrorCode("MINE-SCENE-ASSET-422-001", "三维资源请求不符合安全边界"),
                        Map.of()));
        mockMvc.perform(multipart("/api/v1/mine/sites/{siteId}/scene-assets", siteId)
                        .file(upload))
                .andExpect(status().isUnprocessableEntity());

        SaCheckPermission uploadPermission = SceneAssetController.class
                .getMethod("upload", UUID.class, org.springframework.web.multipart.MultipartFile.class)
                .getAnnotation(SaCheckPermission.class);
        SaCheckPermission readPermission = SceneAssetController.class
                .getMethod("readFile", UUID.class, UUID.class, String.class, String.class, String.class)
                .getAnnotation(SaCheckPermission.class);
        assertThat(uploadPermission.value()).containsExactly("mine:scene:asset:upload");
        assertThat(readPermission.value()).containsExactly("mine:scene:view");
    }
}
