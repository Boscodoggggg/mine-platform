-- 允许保存最大 32 KiB 凭据 JSON 经 AES-GCM 和 Base64URL 编码后的密文。
ALTER TABLE notification_channel
    ALTER COLUMN secret_ciphertext TYPE VARCHAR(65536);

COMMENT ON COLUMN notification_channel.secret_ciphertext IS
    'AES-256-GCM 加密后的通知供应商凭据 JSON 密文，最大支持 32 KiB 明文';
