package com.bosco.mine.server.modules.mine.security;

/**
 * 对人员敏感字段执行带完整性认证的可逆加密。
 */
public interface SensitiveFieldCipher {

    /**
     * 加密敏感字段。
     *
     * @param plaintext 明文
     * @return 带算法版本的密文
     */
    String encrypt(String plaintext);

    /**
     * 解密并认证敏感字段。
     *
     * @param ciphertext 带算法版本的密文
     * @return 明文
     */
    String decrypt(String ciphertext);

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
