package com.bosco.mine.server.infrastructure.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/**
 * 根据标准协议配置装配唯一对象存储实现。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ObjectStorageProperties.class)
@ConditionalOnProperty(prefix = "mine.object-storage", name = "type", havingValue = "s3")
public class ObjectStorageConfiguration {

    /**
     * 创建标准 S3 同步客户端。
     *
     * @param properties 对象存储配置
     * @return 应由 Spring 关闭的 S3 客户端
     */
    @Bean(destroyMethod = "close")
    public S3Client s3Client(ObjectStorageProperties properties) {
        ObjectStorageProperties.S3Properties s3 = properties.s3();
        return S3Client.builder()
                .endpointOverride(s3.endpoint())
                .region(Region.of(s3.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(s3.accessKey(), s3.secretKey())))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(s3.pathStyleAccess())
                        .build())
                .build();
    }

    /**
     * 创建供应商中立的对象存储端口实现。
     *
     * @param client 标准 S3 客户端
     * @param properties 对象存储配置
     * @return 通用对象存储实现
     */
    @Bean
    public ObjectStorage objectStorage(
            S3Client client,
            ObjectStorageProperties properties
    ) {
        return new S3ObjectStorage(client, properties);
    }

    /**
     * 创建可显式启用的开发环境 bucket 初始化器。
     *
     * @param client 标准 S3 客户端
     * @param properties 对象存储配置
     * @return 应用启动阶段执行的 bucket 初始化器
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "mine.object-storage.s3",
            name = "initialize-bucket",
            havingValue = "true")
    public S3BucketInitializer s3BucketInitializer(
            S3Client client,
            ObjectStorageProperties properties
    ) {
        return new S3BucketInitializer(client, properties.s3().bucket());
    }
}
