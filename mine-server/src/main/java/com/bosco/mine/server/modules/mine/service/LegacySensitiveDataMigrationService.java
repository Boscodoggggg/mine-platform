package com.bosco.mine.server.modules.mine.service;

import com.bosco.mine.server.modules.mine.model.LegacyPersonSensitiveData;
import com.bosco.mine.server.modules.mine.repository.MinePersonMapper;
import com.bosco.mine.server.modules.mine.security.SensitiveFieldCipher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

/**
 * 在不记录明文的前提下，将旧版人员敏感密文批量重加密为绑定人员和字段的二版格式。
 */
@Service
public class LegacySensitiveDataMigrationService {

    private static final int BATCH_SIZE = 200;
    private static final UUID KEY_GUARD_PERSON_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String KEY_GUARD_PLAINTEXT =
            "mine-person-data-key-verification-v1";

    private final MinePersonMapper mapper;
    private final SensitiveFieldCipher cipher;
    private final TransactionTemplate transaction;

    /**
     * 创建旧版敏感数据升级服务。
     *
     * @param mapper 人员数据访问接口
     * @param cipher 敏感字段加密器
     * @param transactionManager 事务管理器
     */
    public LegacySensitiveDataMigrationService(
            MinePersonMapper mapper,
            SensitiveFieldCipher cipher,
            PlatformTransactionManager transactionManager
    ) {
        this.mapper = mapper;
        this.cipher = cipher;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * 校验密钥并分批升级全部旧版密文，仍有遗留数据时拒绝完成启动。
     *
     * @return 完成升级的人员数量
     */
    public int reencryptAll() {
        cipher.validateConfiguration();
        verifyKeyIdentity();
        int upgraded = 0;
        while (true) {
            Integer batch = transaction.execute(status -> upgradeBatch());
            if (batch == null || batch == 0) {
                break;
            }
            upgraded += batch;
        }
        completeLegacyUpgrade();
        return upgraded;
    }

    /**
     * 在全局事务锁内校验当前密钥身份；首次运行时先验证已有密文，再建立持久化哨兵。
     */
    private void verifyKeyIdentity() {
        transaction.executeWithoutResult(status -> {
            mapper.lockSensitiveDataKeyGuard();
            String verificationCiphertext = mapper.findKeyVerificationCiphertext().orElse(null);
            if (verificationCiphertext != null) {
                String plaintext = cipher.decrypt(
                        KEY_GUARD_PERSON_ID,
                        SensitiveFieldCipher.FieldType.PHONE,
                        verificationCiphertext);
                if (!MessageDigest.isEqual(
                        KEY_GUARD_PLAINTEXT.getBytes(StandardCharsets.UTF_8),
                        plaintext.getBytes(StandardCharsets.UTF_8))) {
                    throw new IllegalStateException("人员敏感数据密钥身份校验失败");
                }
                return;
            }
            mapper.findSensitiveCiphertextSample().ifPresent(this::verifyExistingCiphertexts);
            String encrypted = cipher.encrypt(
                    KEY_GUARD_PERSON_ID,
                    SensitiveFieldCipher.FieldType.PHONE,
                    KEY_GUARD_PLAINTEXT);
            if (mapper.insertKeyVerificationCiphertext(encrypted) != 1) {
                throw new IllegalStateException("人员敏感数据密钥身份哨兵建立失败");
            }
        });
    }

    /**
     * 使用当前密钥解密一条既有人员样本，避免错误密钥被注册为合法密钥。
     *
     * @param person 既有人员敏感密文样本
     */
    private void verifyExistingCiphertexts(LegacyPersonSensitiveData person) {
        cipher.decrypt(
                person.id(), SensitiveFieldCipher.FieldType.PHONE, person.phoneCiphertext());
        cipher.decrypt(
                person.id(), SensitiveFieldCipher.FieldType.CERTIFICATE,
                person.certificateCiphertext());
    }

    /**
     * 在全局事务锁内确认一版密文已清零，并持久化升级完成状态。
     */
    private void completeLegacyUpgrade() {
        transaction.executeWithoutResult(status -> {
            mapper.lockSensitiveDataKeyGuard();
            if (mapper.countLegacySensitivePeople() != 0) {
                throw new IllegalStateException("人员敏感字段仍存在未完成升级的一版密文");
            }
            if (mapper.markLegacyUpgradeCompleted() != 1) {
                throw new IllegalStateException("人员敏感字段升级完成状态写入失败");
            }
        });
    }

    /**
     * 锁定并升级一批旧版人员敏感密文。
     *
     * @return 本批处理的人员数量
     */
    private int upgradeBatch() {
        List<LegacyPersonSensitiveData> people = mapper.lockLegacySensitivePeople(BATCH_SIZE);
        for (LegacyPersonSensitiveData person : people) {
            String phone = upgradeField(
                    person,
                    SensitiveFieldCipher.FieldType.PHONE,
                    person.phoneCiphertext());
            String certificate = upgradeField(
                    person,
                    SensitiveFieldCipher.FieldType.CERTIFICATE,
                    person.certificateCiphertext());
            int changed = mapper.upgradeSensitiveCiphertexts(
                    person.id(), person.phoneCiphertext(), person.certificateCiphertext(),
                    phone, certificate);
            if (changed != 1) {
                throw new IllegalStateException("人员敏感字段升级期间发生并发修改");
            }
        }
        return people.size();
    }

    /**
     * 将单个一版密文解密后重加密为绑定人员和字段的二版格式。
     *
     * @param person 人员敏感数据
     * @param fieldType 敏感字段类型
     * @param ciphertext 待检查密文
     * @return 原二版密文或升级后的二版密文
     */
    private String upgradeField(
            LegacyPersonSensitiveData person,
            SensitiveFieldCipher.FieldType fieldType,
            String ciphertext
    ) {
        if (!ciphertext.startsWith("v1:")) {
            return ciphertext;
        }
        String plaintext = cipher.decrypt(person.id(), fieldType, ciphertext);
        return cipher.encrypt(person.id(), fieldType, plaintext);
    }
}
