package com.bosco.mine.server.modules.mine.scene.domain;

import org.junit.jupiter.api.Test;

import static com.bosco.mine.server.modules.mine.scene.domain.SceneAssetPolicy.SceneAssetType.GLB;
import static com.bosco.mine.server.modules.mine.scene.domain.SceneAssetPolicy.SceneAssetType.TILES_3D;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证三维资源类型和包内相对路径的纯安全规则。
 */
class SceneAssetPolicyTest {

    @Test
    void 应按文件扩展名和媒体类型识别受支持资源() {
        assertThat(SceneAssetPolicy.typeFor("pit.glb", "model/gltf-binary"))
                .isEqualTo(GLB);
        assertThat(SceneAssetPolicy.typeFor("pit-tiles.zip", "application/zip"))
                .isEqualTo(TILES_3D);
    }

    @Test
    void 扩展名与媒体类型冲突时必须拒绝资源() {
        assertThatThrownBy(() -> SceneAssetPolicy.typeFor(
                "pit.glb", "application/zip"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("资源类型");
        assertThatThrownBy(() -> SceneAssetPolicy.typeFor(
                "pit.obj", "application/octet-stream"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 应保留规范安全的包内相对路径() {
        assertThat(SceneAssetPolicy.safeRelativePath("tiles/0/0.b3dm"))
                .isEqualTo("tiles/0/0.b3dm");
        assertThat(SceneAssetPolicy.safeRelativePath("tileset.json"))
                .isEqualTo("tileset.json");
    }

    @Test
    void 必须拒绝路径穿越绝对路径反斜线和控制字符() {
        assertThatThrownBy(() -> SceneAssetPolicy.safeRelativePath("../secret"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SceneAssetPolicy.safeRelativePath("/tileset.json"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SceneAssetPolicy.safeRelativePath("tiles\\0.b3dm"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SceneAssetPolicy.safeRelativePath("tiles/\u0000.b3dm"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 必须拒绝空目录段当前目录段和末尾斜线() {
        assertThatThrownBy(() -> SceneAssetPolicy.safeRelativePath("tiles//0.b3dm"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SceneAssetPolicy.safeRelativePath("tiles/./0.b3dm"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SceneAssetPolicy.safeRelativePath("tiles/"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
