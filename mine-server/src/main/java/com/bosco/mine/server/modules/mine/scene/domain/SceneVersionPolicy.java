package com.bosco.mine.server.modules.mine.scene.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 定义场景变换、图层规范化和不可变发布摘要的纯领域规则。
 */
public final class SceneVersionPolicy {

    private static final Pattern LAYER_CODE = Pattern.compile("[a-z][a-z0-9-]{1,63}");
    private static final double MAX_ABSOLUTE_COORDINATE = 1_000_000_000D;
    private static final double MAX_DISTANCE = 1_000_000_000D;
    private static final double MAX_SCALE = 1_000_000D;
    private static final int MAX_LAYERS = 256;
    private static final int MAX_LAYER_ITEMS = 100_000;

    private SceneVersionPolicy() {
    }

    /**
     * 校验场景或图层的有限平移、旋转和缩放。
     *
     * @param origin 平移或场景原点
     * @param rotation 欧拉旋转角
     * @param scale 统一缩放
     */
    public static void validateTransform(Vector3 origin, Rotation rotation, double scale) {
        Objects.requireNonNull(origin, "场景变换原点不能为空");
        Objects.requireNonNull(rotation, "场景旋转不能为空");
        requireFiniteBounded(origin.x(), MAX_ABSOLUTE_COORDINATE, "X 坐标");
        requireFiniteBounded(origin.y(), MAX_ABSOLUTE_COORDINATE, "Y 坐标");
        requireFiniteBounded(origin.z(), MAX_ABSOLUTE_COORDINATE, "Z 坐标");
        requireAngle(rotation.headingDegrees(), "航向角");
        requireAngle(rotation.pitchDegrees(), "俯仰角");
        requireAngle(rotation.rollDegrees(), "横滚角");
        if (!Double.isFinite(scale) || scale <= 0D || scale > MAX_SCALE) {
            throw new IllegalArgumentException("场景缩放超出允许范围");
        }
    }

    /**
     * 校验图层并按顺序返回不可变规范化列表。
     *
     * @param layers 待规范化图层
     * @return 按顺序排列的不可变图层
     */
    public static List<LayerDefinition> normalizeLayers(List<LayerDefinition> layers) {
        Objects.requireNonNull(layers, "场景图层不能为空");
        if (layers.size() > MAX_LAYERS) {
            throw new IllegalArgumentException("场景图层数量超出允许范围");
        }
        ArrayList<LayerDefinition> normalized = new ArrayList<>(layers.size());
        Set<String> codes = new HashSet<>();
        Set<Integer> orders = new HashSet<>();
        for (LayerDefinition layer : layers) {
            validateLayer(Objects.requireNonNull(layer, "场景图层项不能为空"));
            if (!codes.add(layer.code())) {
                throw new IllegalArgumentException("场景图层编码不能重复");
            }
            if (!orders.add(layer.orderNumber())) {
                throw new IllegalArgumentException("场景图层顺序不能重复");
            }
            normalized.add(layer);
        }
        normalized.sort(Comparator.comparingInt(LayerDefinition::orderNumber));
        return List.copyOf(normalized);
    }

    /**
     * 为场景和规范化图层计算确定性 SHA-256 发布摘要。
     *
     * @param scene 场景坐标定义
     * @param layers 场景图层
     * @param defaultCameraJson 数据库规范化后的默认相机 JSON
     * @param fallbackViewportJson 数据库规范化后的二维降级视口 JSON
     * @param layerOptionsJsonByCode 按图层编码绑定的数据库规范化选项 JSON
     * @return 带 `sha256:` 前缀的内容摘要
     */
    public static String contentHash(
            SceneDefinition scene,
            List<LayerDefinition> layers,
            String defaultCameraJson,
            String fallbackViewportJson,
            Map<String, String> layerOptionsJsonByCode
    ) {
        SceneDefinition checkedScene = Objects.requireNonNull(scene, "场景定义不能为空");
        Objects.requireNonNull(checkedScene.coordinateSystemId(), "场景坐标系不能为空");
        Objects.requireNonNull(checkedScene.coordinateMode(), "场景坐标模式不能为空");
        validateTransform(checkedScene.origin(), checkedScene.rotation(), checkedScene.scale());
        List<LayerDefinition> normalized = normalizeLayers(layers);
        String checkedCameraJson = requireJsonText(defaultCameraJson, "默认相机 JSON");
        String checkedViewportJson = requireJsonText(fallbackViewportJson, "二维视口 JSON");
        Map<String, String> checkedOptions = Map.copyOf(
                Objects.requireNonNull(layerOptionsJsonByCode, "图层选项不能为空"));
        if (checkedOptions.size() != normalized.size()) {
            throw new IllegalArgumentException("图层选项与图层定义不完整");
        }

        StringBuilder canonical = new StringBuilder(512);
        append(canonical, checkedScene.coordinateSystemId());
        append(canonical, checkedScene.coordinateMode());
        append(canonical, checkedScene.origin());
        append(canonical, checkedScene.rotation());
        append(canonical, checkedScene.scale());
        append(canonical, checkedCameraJson);
        append(canonical, checkedViewportJson);
        for (LayerDefinition layer : normalized) {
            append(canonical, layer.code());
            append(canonical, layer.orderNumber());
            append(canonical, layer.type());
            append(canonical, layer.assetId());
            append(canonical, layer.assetType());
            append(canonical, layer.visible());
            append(canonical, layer.opacity());
            append(canonical, layer.minimumDistance());
            append(canonical, layer.maximumDistance());
            append(canonical, layer.offset());
            append(canonical, layer.rotation());
            append(canonical, layer.scale());
            append(canonical, layer.maxItems());
            append(canonical, requireJsonText(
                    checkedOptions.get(layer.code()), "图层选项 JSON"));
        }
        return "sha256:" + HexFormat.of().formatHex(sha256(canonical.toString()));
    }

    private static String requireJsonText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return value;
    }

    private static void validateLayer(LayerDefinition layer) {
        if (layer.code() == null || !LAYER_CODE.matcher(layer.code()).matches()) {
            throw new IllegalArgumentException("场景图层编码不合法");
        }
        if (layer.orderNumber() < 1 || layer.orderNumber() > 10_000) {
            throw new IllegalArgumentException("场景图层顺序超出允许范围");
        }
        LayerType type = Objects.requireNonNull(layer.type(), "场景图层类型不能为空");
        if (type == LayerType.STATIC_GLB) {
            requireAssetType(layer, SceneAssetPolicy.SceneAssetType.GLB);
        } else if (type == LayerType.STATIC_3D_TILES) {
            requireAssetType(layer, SceneAssetPolicy.SceneAssetType.TILES_3D);
        } else if (layer.assetId() != null || layer.assetType() != null) {
            throw new IllegalArgumentException("动态或网格图层不能引用静态资源");
        }
        if (!Double.isFinite(layer.opacity())
                || layer.opacity() < 0D || layer.opacity() > 1D) {
            throw new IllegalArgumentException("场景图层透明度超出允许范围");
        }
        requireDistance(layer.minimumDistance(), "最小显示距离");
        requireDistance(layer.maximumDistance(), "最大显示距离");
        if (layer.minimumDistance() > layer.maximumDistance()) {
            throw new IllegalArgumentException("最小显示距离不能大于最大显示距离");
        }
        validateTransform(layer.offset(), layer.rotation(), layer.scale());
        if (layer.maxItems() < 0 || layer.maxItems() > MAX_LAYER_ITEMS) {
            throw new IllegalArgumentException("场景图层对象上限超出允许范围");
        }
    }

    private static void requireAssetType(
            LayerDefinition layer,
            SceneAssetPolicy.SceneAssetType expected
    ) {
        if (layer.assetId() == null || layer.assetType() != expected) {
            throw new IllegalArgumentException("静态图层资源类型不匹配");
        }
    }

    private static void requireDistance(double value, String label) {
        if (!Double.isFinite(value) || value < 0D || value > MAX_DISTANCE) {
            throw new IllegalArgumentException(label + "超出允许范围");
        }
    }

    private static void requireFiniteBounded(double value, double bound, String label) {
        if (!Double.isFinite(value) || Math.abs(value) > bound) {
            throw new IllegalArgumentException(label + "超出允许范围");
        }
    }

    private static void requireAngle(double value, String label) {
        if (!Double.isFinite(value) || value < -360D || value > 360D) {
            throw new IllegalArgumentException(label + "超出允许范围");
        }
    }

    private static byte[] sha256(String canonical) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 缺少 SHA-256 算法", exception);
        }
    }

    private static void append(StringBuilder target, Object value) {
        String text;
        if (value instanceof Double number) {
            text = Double.toHexString(number);
        } else if (value instanceof Vector3 vector) {
            text = Double.toHexString(vector.x()) + ','
                    + Double.toHexString(vector.y()) + ','
                    + Double.toHexString(vector.z());
        } else if (value instanceof Rotation rotation) {
            text = Double.toHexString(rotation.headingDegrees()) + ','
                    + Double.toHexString(rotation.pitchDegrees()) + ','
                    + Double.toHexString(rotation.rollDegrees());
        } else {
            text = String.valueOf(value);
        }
        target.append(text.length()).append(':').append(text).append(';');
    }

    /** 场景坐标锚点模式。 */
    public enum CoordinateMode {
        /** 只表达矿区局部东、北、天坐标。 */
        LOCAL_ENU,
        /** 使用显式 WGS84 经纬高锚点建立本地坐标。 */
        WGS84_ANCHOR
    }

    /** 场景固定图层类型。 */
    public enum LayerType {
        /** 静态 3D Tiles 文件树。 */
        STATIC_3D_TILES,
        /** 静态 GLB 模型。 */
        STATIC_GLB,
        /** 无外部资源的坐标网格。 */
        GRID,
        /** 车辆、人员和标签当前位置。 */
        DYNAMIC_LOCATIONS,
        /** 已发布途径点和围栏。 */
        DYNAMIC_REGIONS,
        /** 选中对象的有界历史轨迹。 */
        DYNAMIC_TRAJECTORY,
        /** 可映射到当前位置的未关闭告警。 */
        DYNAMIC_ALARMS,
        /** 当前班次、作业单和运营变更。 */
        DYNAMIC_OPERATIONS
    }

    /**
     * 三维有限向量。
     *
     * @param x X 分量
     * @param y Y 分量
     * @param z Z 分量
     */
    public record Vector3(double x, double y, double z) {
    }

    /**
     * 以度表示的航向、俯仰和横滚角。
     *
     * @param headingDegrees 航向角
     * @param pitchDegrees 俯仰角
     * @param rollDegrees 横滚角
     */
    public record Rotation(
            double headingDegrees,
            double pitchDegrees,
            double rollDegrees
    ) {
    }

    /**
     * 参与发布摘要的场景坐标定义。
     *
     * @param coordinateSystemId 矿区统一坐标系标识
     * @param coordinateMode 场景锚点模式
     * @param origin 场景原点
     * @param rotation 场景旋转
     * @param scale 场景统一缩放
     */
    public record SceneDefinition(
            UUID coordinateSystemId,
            CoordinateMode coordinateMode,
            Vector3 origin,
            Rotation rotation,
            double scale
    ) {
    }

    /**
     * 参与发布校验和摘要的规范化图层定义。
     *
     * @param code 稳定图层编码
     * @param orderNumber 图层顺序
     * @param type 固定图层类型
     * @param assetId 静态资源标识
     * @param assetType 静态资源类型
     * @param visible 默认是否可见
     * @param opacity 默认透明度
     * @param minimumDistance 最小显示距离
     * @param maximumDistance 最大显示距离
     * @param offset 图层局部偏移
     * @param rotation 图层局部旋转
     * @param scale 图层局部缩放
     * @param maxItems 动态对象上限
     */
    public record LayerDefinition(
            String code,
            int orderNumber,
            LayerType type,
            UUID assetId,
            SceneAssetPolicy.SceneAssetType assetType,
            boolean visible,
            double opacity,
            double minimumDistance,
            double maximumDistance,
            Vector3 offset,
            Rotation rotation,
            double scale,
            int maxItems
    ) {

        /** 返回替换静态资源后的副本。 */
        public LayerDefinition withAsset(
                UUID replacementAssetId,
                SceneAssetPolicy.SceneAssetType replacementAssetType
        ) {
            return new LayerDefinition(
                    code, orderNumber, type, replacementAssetId, replacementAssetType,
                    visible, opacity, minimumDistance, maximumDistance,
                    offset, rotation, scale, maxItems);
        }

        /** 返回替换透明度后的副本。 */
        public LayerDefinition withOpacity(double replacementOpacity) {
            return new LayerDefinition(
                    code, orderNumber, type, assetId, assetType,
                    visible, replacementOpacity, minimumDistance, maximumDistance,
                    offset, rotation, scale, maxItems);
        }

        /** 返回替换显示距离后的副本。 */
        public LayerDefinition withDistances(double minimum, double maximum) {
            return new LayerDefinition(
                    code, orderNumber, type, assetId, assetType,
                    visible, opacity, minimum, maximum,
                    offset, rotation, scale, maxItems);
        }

        /** 返回替换动态对象上限后的副本。 */
        public LayerDefinition withMaxItems(int replacementMaxItems) {
            return new LayerDefinition(
                    code, orderNumber, type, assetId, assetType,
                    visible, opacity, minimumDistance, maximumDistance,
                    offset, rotation, scale, replacementMaxItems);
        }
    }
}
