package com.bosco.mine.server.modules.mine.scene.domain;

import java.util.Locale;
import java.util.Set;

/**
 * 定义三维资源类型识别和包内路径的纯安全规则。
 */
public final class SceneAssetPolicy {

    private static final int MAX_RELATIVE_PATH_LENGTH = 1024;
    private static final Set<String> GLB_MEDIA_TYPES = Set.of(
            "model/gltf-binary", "application/octet-stream");
    private static final Set<String> ZIP_MEDIA_TYPES = Set.of(
            "application/zip", "application/x-zip-compressed");

    private SceneAssetPolicy() {
    }

    /**
     * 根据文件扩展名和媒体类型识别受支持的三维资源类型。
     *
     * @param fileName 原始文件名
     * @param contentType 上传媒体类型
     * @return 固定三维资源类型
     */
    public static SceneAssetType typeFor(String fileName, String contentType) {
        String normalizedName = requireText(fileName, "三维资源文件名不能为空")
                .toLowerCase(Locale.ROOT);
        String normalizedType = requireText(contentType, "三维资源媒体类型不能为空")
                .split(";", 2)[0]
                .strip()
                .toLowerCase(Locale.ROOT);
        if (normalizedName.endsWith(".glb") && GLB_MEDIA_TYPES.contains(normalizedType)) {
            return SceneAssetType.GLB;
        }
        if (normalizedName.endsWith(".zip") && ZIP_MEDIA_TYPES.contains(normalizedType)) {
            return SceneAssetType.TILES_3D;
        }
        throw new IllegalArgumentException("三维资源类型与文件扩展名或媒体类型不匹配");
    }

    /**
     * 校验并返回不含平台相关歧义的包内安全相对路径。
     *
     * @param rawPath 原始包内路径
     * @return 已校验相对路径
     */
    public static String safeRelativePath(String rawPath) {
        String path = requireText(rawPath, "三维资源相对路径不能为空");
        if (path.length() > MAX_RELATIVE_PATH_LENGTH
                || path.startsWith("/")
                || path.endsWith("/")
                || path.indexOf('\\') >= 0
                || path.indexOf(':') >= 0
                || path.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("三维资源相对路径不安全");
        }
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("三维资源相对路径不安全");
            }
        }
        return path;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }

    /** 三维资源固定类型。 */
    public enum SceneAssetType {
        /** 单文件二进制 glTF 2.0 模型。 */
        GLB,
        /** 以安全 ZIP 包上传的 3D Tiles 文件树。 */
        TILES_3D
    }
}
