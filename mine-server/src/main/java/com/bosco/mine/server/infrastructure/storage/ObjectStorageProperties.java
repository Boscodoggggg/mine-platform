package com.bosco.mine.server.infrastructure.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 通用对象存储配置。
 *
 * @param type 存储协议类型
 * @param s3 标准 S3 连接配置
 */
@ConfigurationProperties("mine.object-storage")
public record ObjectStorageProperties(StorageType type, S3Properties s3) {

    /** 校验对象存储类型和对应连接配置。 */
    public ObjectStorageProperties {
        type = Objects.requireNonNull(type, "对象存储类型不能为空");
        if (type == StorageType.S3) {
            Objects.requireNonNull(s3, "S3 对象存储配置不能为空");
        }
    }

    /**
     * 当前支持的对象存储协议类型。
     */
    public enum StorageType {
        /** 标准 S3 兼容协议。 */
        S3
    }

    /**
     * 标准 S3 连接参数。
     *
     * @param endpoint S3 兼容服务端点
     * @param region 签名区域
     * @param bucket 默认业务 bucket
     * @param accessKey 访问密钥标识
     * @param secretKey 访问密钥
     * @param pathStyleAccess 是否使用 path-style 地址
     */
    public record S3Properties(
            URI endpoint,
            String region,
            String bucket,
            String accessKey,
            String secretKey,
            boolean pathStyleAccess
    ) {

        private static final Pattern BUCKET = Pattern.compile(
                "[a-z0-9](?:[a-z0-9.-]{1,61}[a-z0-9])?");

        /** 校验并规范化标准 S3 连接参数。 */
        public S3Properties {
            endpoint = Objects.requireNonNull(endpoint, "S3 endpoint 不能为空").normalize();
            if (!"http".equalsIgnoreCase(endpoint.getScheme())
                    && !"https".equalsIgnoreCase(endpoint.getScheme())) {
                throw new IllegalArgumentException("S3 endpoint 必须使用 HTTP 或 HTTPS");
            }
            region = requireText(region, "S3 region 不能为空");
            bucket = requireText(bucket, "S3 bucket 不能为空");
            if (!BUCKET.matcher(bucket).matches()
                    || bucket.contains("..")
                    || looksLikeIpv4(bucket)) {
                throw new IllegalArgumentException("S3 bucket 格式无效");
            }
            accessKey = requireText(accessKey, "S3 access-key 不能为空");
            secretKey = requireText(secretKey, "S3 secret-key 不能为空");
        }

        private static String requireText(String value, String message) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(message);
            }
            return value.trim();
        }

        private static boolean looksLikeIpv4(String value) {
            return value.matches("(?:\\d{1,3}\\.){3}\\d{1,3}");
        }
    }
}
