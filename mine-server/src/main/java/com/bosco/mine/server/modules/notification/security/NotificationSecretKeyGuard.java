package com.bosco.mine.server.modules.notification.security;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * 在应用接受流量前校验通知独立密钥格式与持久化身份。
 */
@Component
public class NotificationSecretKeyGuard implements ApplicationRunner {

    private static final UUID GUARD_OWNER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String GUARD_PLAINTEXT =
            "mine-notification-data-key-verification-v1";

    private final NotificationSecretKeyGuardMapper mapper;
    private final NotificationSecretCipher cipher;
    private final NotificationLegacySecretMigrator legacySecretMigrator;
    private final TransactionTemplate transaction;

    /**
     * 创建通知数据密钥启动门禁。
     *
     * @param mapper 密钥身份数据接口
     * @param cipher 通知敏感数据加密器
     * @param legacySecretMigrator 历史明文敏感字段迁移器
     * @param transactionManager 事务管理器
     */
    public NotificationSecretKeyGuard(
            NotificationSecretKeyGuardMapper mapper,
            NotificationSecretCipher cipher,
            NotificationLegacySecretMigrator legacySecretMigrator,
            PlatformTransactionManager transactionManager
    ) {
        this.mapper = mapper;
        this.cipher = cipher;
        this.legacySecretMigrator = legacySecretMigrator;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** 应用启动时执行不可绕过的通知密钥身份校验。 */
    @Override
    public void run(ApplicationArguments arguments) {
        initialize();
    }

    /**
     * 主动加载密钥，并在事务锁内验证或建立身份哨兵。
     */
    public void initialize() {
        cipher.validateConfiguration();
        transaction.executeWithoutResult(status -> {
            verifyIdentity();
            legacySecretMigrator.migrate();
        });
    }

    private void verifyIdentity() {
        mapper.lockKeyGuard();
        String verificationCiphertext = mapper.findVerificationCiphertext().orElse(null);
        if (verificationCiphertext != null) {
            verifyGuardPlaintext(cipher.decrypt(
                    GUARD_OWNER_ID,
                    NotificationSecretCipher.SecretPurpose.CHANNEL_CREDENTIAL,
                    0,
                    verificationCiphertext));
            return;
        }
        mapper.findCredentialSample().ifPresent(sample -> cipher.decrypt(
                sample.id(),
                NotificationSecretCipher.SecretPurpose.CHANNEL_CREDENTIAL,
                sample.version(),
                sample.secretCiphertext()));
        String encrypted = cipher.encrypt(
                GUARD_OWNER_ID,
                NotificationSecretCipher.SecretPurpose.CHANNEL_CREDENTIAL,
                0,
                GUARD_PLAINTEXT);
        if (mapper.insertVerificationCiphertext(encrypted) != 1) {
            throw new IllegalStateException("通知数据密钥身份哨兵建立失败");
        }
    }

    private void verifyGuardPlaintext(String plaintext) {
        if (!MessageDigest.isEqual(
                GUARD_PLAINTEXT.getBytes(StandardCharsets.UTF_8),
                plaintext.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalStateException("通知数据密钥身份校验失败");
        }
    }
}
