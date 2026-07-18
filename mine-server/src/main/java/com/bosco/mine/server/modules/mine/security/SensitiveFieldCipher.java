package com.bosco.mine.server.modules.mine.security;

import java.util.UUID;

/**
 * 对人员敏感字段执行带完整性认证的可逆加密。
 */
public interface SensitiveFieldCipher {

    /**
     * 主动校验外部密钥等加密配置，供启动门禁在接收请求前失败退出。
     */
    void validateConfiguration();

    /**
     * 加密敏感字段。
     *
     * @param personId 密文所属人员标识
     * @param fieldType 敏感字段类型
     * @param plaintext 明文
     * @return 带算法版本的密文
     */
    String encrypt(UUID personId, FieldType fieldType, String plaintext);

    /**
     * 解密并认证敏感字段。
     *
     * @param personId 密文所属人员标识
     * @param fieldType 敏感字段类型
     * @param ciphertext 带算法版本的密文
     * @return 明文
     */
    String decrypt(UUID personId, FieldType fieldType, String ciphertext);

    /**
     * 区分同一人员的不同敏感字段，防止合法密文被交换。
     */
    enum FieldType {
        /** 手机号码。 */
        PHONE,
        /** 证件号码。 */
        CERTIFICATE
    }

    /**
     * 表示密文格式无效、密钥错误或认证标签校验失败。
     */
    final class SensitiveFieldCipherException extends RuntimeException {

        /**
         * 创建不暴露敏感数据的密文处理异常。
         *
         * @param message 安全错误说明
         * @param cause 原始异常
         */
        public SensitiveFieldCipherException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
