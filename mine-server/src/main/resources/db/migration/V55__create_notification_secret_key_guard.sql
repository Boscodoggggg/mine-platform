-- 持久化通知数据密钥身份，阻止缺失密钥或错误轮换后的实例进入健康状态。
CREATE TABLE notification_secret_key_guard (
    purpose                 VARCHAR(64) NOT NULL,
    verification_ciphertext TEXT        NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_notification_secret_key_guard PRIMARY KEY (purpose),
    CONSTRAINT ck_notification_secret_key_guard_purpose
        CHECK (purpose = 'NOTIFICATION_DATA'),
    CONSTRAINT ck_notification_secret_key_guard_ciphertext
        CHECK (verification_ciphertext ~ '^v1:[A-Za-z0-9_-]+:[A-Za-z0-9_-]+$'),
    CONSTRAINT ck_notification_secret_key_guard_audit
        CHECK (updated_at >= created_at)
);

COMMENT ON TABLE notification_secret_key_guard IS '通知敏感数据密钥身份启动门禁';
COMMENT ON COLUMN notification_secret_key_guard.purpose IS '通知数据密钥固定用途标识';
COMMENT ON COLUMN notification_secret_key_guard.verification_ciphertext IS '用于验证通知数据密钥身份的认证密文';
COMMENT ON COLUMN notification_secret_key_guard.created_at IS '密钥身份哨兵创建时间';
COMMENT ON COLUMN notification_secret_key_guard.updated_at IS '密钥身份哨兵最近更新时间';
