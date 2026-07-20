package com.bosco.mine.server.modules.mine.scene.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.bosco.mine.server.modules.mine.scene.domain.SceneAssetPolicy.SceneAssetType.GLB;
import static com.bosco.mine.server.modules.mine.scene.domain.SceneAssetPolicy.SceneAssetType.TILES_3D;
import static com.bosco.mine.server.modules.mine.scene.domain.SceneVersionPolicy.LayerType.DYNAMIC_LOCATIONS;
import static com.bosco.mine.server.modules.mine.scene.domain.SceneVersionPolicy.LayerType.GRID;
import static com.bosco.mine.server.modules.mine.scene.domain.SceneVersionPolicy.LayerType.STATIC_3D_TILES;
import static com.bosco.mine.server.modules.mine.scene.domain.SceneVersionPolicy.LayerType.STATIC_GLB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证场景变换、图层规范化和发布摘要的纯领域规则。
 */
class SceneVersionPolicyTest {

    private static final UUID COORDINATE_SYSTEM_ID = UUID.fromString(
            "019b0000-0000-7000-8000-000000000101");
    private static final UUID ASSET_ID = UUID.fromString(
            "019b0000-0000-7000-8000-000000000102");

    @Test
    void 合法变换应通过且非有限或越界数值必须拒绝() {
        SceneVersionPolicy.validateTransform(
                vector(100, 200, 5), rotation(15, -5, 1), 1.25);

        assertThatThrownBy(() -> SceneVersionPolicy.validateTransform(
                vector(Double.NaN, 0, 0), rotation(0, 0, 0), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SceneVersionPolicy.validateTransform(
                vector(0, 0, 0), rotation(361, 0, 0), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SceneVersionPolicy.validateTransform(
                vector(0, 0, 0), rotation(0, 0, 0), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 图层应按顺序规范化且静态层资源类型必须匹配() {
        SceneVersionPolicy.LayerDefinition locations = dynamicLayer(
                "locations", 20, DYNAMIC_LOCATIONS);
        SceneVersionPolicy.LayerDefinition terrain = staticLayer(
                "terrain", 10, STATIC_3D_TILES, TILES_3D);

        assertThat(SceneVersionPolicy.normalizeLayers(List.of(locations, terrain)))
                .extracting(SceneVersionPolicy.LayerDefinition::code)
                .containsExactly("terrain", "locations");

        assertThatThrownBy(() -> SceneVersionPolicy.normalizeLayers(List.of(
                staticLayer("wrong", 1, STATIC_3D_TILES, GLB))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("资源类型");
        assertThatThrownBy(() -> SceneVersionPolicy.normalizeLayers(List.of(
                staticLayer("wrong", 1, STATIC_GLB, TILES_3D))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 动态层和网格层不得伪造静态资源引用() {
        assertThatThrownBy(() -> SceneVersionPolicy.normalizeLayers(List.of(
                dynamicLayer("locations", 1, DYNAMIC_LOCATIONS).withAsset(
                        ASSET_ID, GLB))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SceneVersionPolicy.normalizeLayers(List.of(
                dynamicLayer("grid", 1, GRID).withAsset(ASSET_ID, GLB))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 图层编码和顺序必须唯一且显示参数必须有界() {
        SceneVersionPolicy.LayerDefinition first = dynamicLayer(
                "locations", 1, DYNAMIC_LOCATIONS);

        assertThatThrownBy(() -> SceneVersionPolicy.normalizeLayers(List.of(
                first, dynamicLayer("locations", 2, GRID))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("编码");
        assertThatThrownBy(() -> SceneVersionPolicy.normalizeLayers(List.of(
                first, dynamicLayer("grid", 1, GRID))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("顺序");
        assertThatThrownBy(() -> SceneVersionPolicy.normalizeLayers(List.of(
                first.withOpacity(1.01))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SceneVersionPolicy.normalizeLayers(List.of(
                first.withDistances(1000, 100))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 发布摘要应与输入顺序无关并随业务内容变化() {
        SceneVersionPolicy.SceneDefinition scene = scene();
        SceneVersionPolicy.LayerDefinition terrain = staticLayer(
                "terrain", 10, STATIC_3D_TILES, TILES_3D);
        SceneVersionPolicy.LayerDefinition locations = dynamicLayer(
                "locations", 20, DYNAMIC_LOCATIONS);

        String first = SceneVersionPolicy.contentHash(
                scene, List.of(terrain, locations));
        String reordered = SceneVersionPolicy.contentHash(
                scene, List.of(locations, terrain));
        String changed = SceneVersionPolicy.contentHash(
                scene, List.of(terrain, locations.withMaxItems(500)));

        assertThat(first).matches("sha256:[0-9a-f]{64}");
        assertThat(reordered).isEqualTo(first);
        assertThat(changed).isNotEqualTo(first);
    }

    private SceneVersionPolicy.SceneDefinition scene() {
        return new SceneVersionPolicy.SceneDefinition(
                COORDINATE_SYSTEM_ID,
                SceneVersionPolicy.CoordinateMode.LOCAL_ENU,
                vector(100, 200, 5),
                rotation(15, -5, 1),
                1.25);
    }

    private SceneVersionPolicy.LayerDefinition staticLayer(
            String code,
            int order,
            SceneVersionPolicy.LayerType type,
            SceneAssetPolicy.SceneAssetType assetType
    ) {
        return new SceneVersionPolicy.LayerDefinition(
                code, order, type, ASSET_ID, assetType, true,
                1, 0, 100_000, vector(0, 0, 0),
                rotation(0, 0, 0), 1, 0);
    }

    private SceneVersionPolicy.LayerDefinition dynamicLayer(
            String code,
            int order,
            SceneVersionPolicy.LayerType type
    ) {
        return new SceneVersionPolicy.LayerDefinition(
                code, order, type, null, null, true,
                1, 0, 100_000, vector(0, 0, 0),
                rotation(0, 0, 0), 1, 10_000);
    }

    private SceneVersionPolicy.Vector3 vector(double x, double y, double z) {
        return new SceneVersionPolicy.Vector3(x, y, z);
    }

    private SceneVersionPolicy.Rotation rotation(
            double heading,
            double pitch,
            double roll
    ) {
        return new SceneVersionPolicy.Rotation(heading, pitch, roll);
    }
}
