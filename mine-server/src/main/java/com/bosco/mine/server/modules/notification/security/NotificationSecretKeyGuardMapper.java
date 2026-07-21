package com.bosco.mine.server.modules.notification.security;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 访问通知数据密钥身份哨兵和既有凭据样本。
 */
@Mapper
public interface NotificationSecretKeyGuardMapper {

    /** 串行化通知密钥身份建立和校验。 */
    @Select("SELECT pg_advisory_xact_lock(-487311842021)")
    String lockKeyGuard();

    /** 查询并锁定已有通知密钥身份哨兵。 */
    @Select("""
            SELECT verification_ciphertext
            FROM notification_secret_key_guard
            WHERE purpose = 'NOTIFICATION_DATA'
            FOR UPDATE
            """)
    Optional<String> findVerificationCiphertext();

    /** 首次启动时建立通知密钥身份哨兵。 */
    @Insert("""
            INSERT INTO notification_secret_key_guard (
                purpose, verification_ciphertext
            ) VALUES ('NOTIFICATION_DATA', #{verificationCiphertext})
            """)
    int insertVerificationCiphertext(
            @Param("verificationCiphertext") String verificationCiphertext
    );

    /** 查询一条既有渠道凭据密文，用于建立哨兵前认证当前密钥。 */
    @Select("""
            SELECT id, version, secret_ciphertext AS secretCiphertext
            FROM notification_channel
            WHERE secret_ciphertext IS NOT NULL
            ORDER BY id
            LIMIT 1
            """)
    Optional<CredentialSample> findCredentialSample();

    /** 查询一批仍把通用 Webhook 地址保存在非敏感 JSON 中的历史渠道。 */
    @Select("""
            SELECT id, version, configuration::text AS configurationJson,
                   secret_ciphertext AS secretCiphertext
            FROM notification_channel
            WHERE provider = 'generic-webhook'
              AND jsonb_exists(configuration, 'endpointUrl')
            ORDER BY id
            LIMIT 100
            FOR UPDATE
            """)
    List<LegacyWebhookChannel> findLegacyWebhookChannels();

    /**
     * 将历史 Webhook 地址移入密文并提升配置版本。
     *
     * @param id 渠道标识
     * @param expectedVersion 迁移前版本
     * @param configurationJson 已移除敏感地址的配置 JSON
     * @param secretCiphertext 使用新版本绑定的完整凭据密文
     * @return 影响行数
     */
    @Update("""
            UPDATE notification_channel
            SET configuration = CAST(#{configurationJson} AS JSONB),
                secret_ciphertext = #{secretCiphertext},
                updated_at = CURRENT_TIMESTAMP,
                version = version + 1
            WHERE id = #{id}
              AND version = #{expectedVersion}
              AND provider = 'generic-webhook'
              AND jsonb_exists(configuration, 'endpointUrl')
            """)
    int migrateLegacyWebhookChannel(
            @Param("id") UUID id,
            @Param("expectedVersion") long expectedVersion,
            @Param("configurationJson") String configurationJson,
            @Param("secretCiphertext") String secretCiphertext
    );

    /**
     * 既有通知渠道凭据样本。
     *
     * @param id 渠道标识
     * @param version 凭据绑定的渠道版本
     * @param secretCiphertext 渠道凭据密文
     */
    record CredentialSample(UUID id, long version, String secretCiphertext) {
        /** 返回不包含密文的安全日志摘要。 */
        @Override
        public String toString() {
            return "CredentialSample[id=" + id + ", version=" + version + "]";
        }
    }

    /**
     * 历史通用 Webhook 渠道迁移快照。
     *
     * @param id 渠道标识
     * @param version 迁移前配置版本
     * @param configurationJson 含历史端点地址的配置 JSON
     * @param secretCiphertext 既有凭据密文
     */
    record LegacyWebhookChannel(
            UUID id,
            long version,
            String configurationJson,
            String secretCiphertext
    ) {
        /** 返回不包含配置和密文的安全日志摘要。 */
        @Override
        public String toString() {
            return "LegacyWebhookChannel[id=" + id + ", version=" + version + "]";
        }
    }
}
