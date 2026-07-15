-- 产品、设备和 MQTT 凭据是矿区设备接入的主数据；归档只改变状态，不物理删除台账。
CREATE TABLE dev_product (
    id          UUID         NOT NULL,
    product_key VARCHAR(64)  NOT NULL,
    name        VARCHAR(128) NOT NULL,
    device_type VARCHAR(64)  NOT NULL,
    status      VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
    archived_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  UUID,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  UUID,
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_dev_product PRIMARY KEY (id),
    CONSTRAINT uk_dev_product_key UNIQUE (product_key),
    CONSTRAINT ck_dev_product_key CHECK (product_key ~ '^[a-z][a-z0-9-]{2,63}$'),
    CONSTRAINT ck_dev_product_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_dev_product_type CHECK (btrim(device_type) <> ''),
    CONSTRAINT ck_dev_product_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_dev_product_archive CHECK (
        (status = 'ARCHIVED' AND archived_at IS NOT NULL)
        OR (status <> 'ARCHIVED' AND archived_at IS NULL)
    ),
    CONSTRAINT ck_dev_product_version CHECK (version >= 0)
);

CREATE INDEX idx_dev_product_status_name
    ON dev_product (status, name, id);

CREATE TABLE dev_device (
    id          UUID         NOT NULL,
    product_id  UUID         NOT NULL,
    device_code VARCHAR(128) NOT NULL,
    name        VARCHAR(128) NOT NULL,
    status      VARCHAR(32)  NOT NULL DEFAULT 'ENABLED',
    archived_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  UUID,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  UUID,
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_dev_device PRIMARY KEY (id),
    CONSTRAINT uk_dev_device_code UNIQUE (device_code),
    CONSTRAINT fk_dev_device_product FOREIGN KEY (product_id) REFERENCES dev_product (id),
    CONSTRAINT ck_dev_device_code CHECK (btrim(device_code) <> ''),
    CONSTRAINT ck_dev_device_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_dev_device_status CHECK (status IN ('ENABLED', 'DISABLED', 'ARCHIVED')),
    CONSTRAINT ck_dev_device_archive CHECK (
        (status = 'ARCHIVED' AND archived_at IS NOT NULL)
        OR (status <> 'ARCHIVED' AND archived_at IS NULL)
    ),
    CONSTRAINT ck_dev_device_version CHECK (version >= 0)
);

CREATE INDEX idx_dev_device_product_status
    ON dev_device (product_id, status, name, id);

-- 业务库只保存 BCrypt/Argon2 等不可逆散列；设备首次密钥通过受控接口只返回一次。
CREATE TABLE dev_device_credential (
    id          UUID         NOT NULL,
    device_id   UUID         NOT NULL,
    username    VARCHAR(128) NOT NULL,
    secret_hash VARCHAR(255) NOT NULL,
    secret_hint VARCHAR(16)  NOT NULL,
    topic_root  VARCHAR(255) NOT NULL,
    status      VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    expires_at  TIMESTAMPTZ,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  UUID,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  UUID,
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_dev_device_credential PRIMARY KEY (id),
    CONSTRAINT uk_dev_device_credential_username UNIQUE (username),
    CONSTRAINT fk_dev_device_credential_device FOREIGN KEY (device_id) REFERENCES dev_device (id),
    CONSTRAINT ck_dev_device_credential_hash CHECK (btrim(secret_hash) <> ''),
    CONSTRAINT ck_dev_device_credential_hint CHECK (char_length(secret_hint) BETWEEN 4 AND 16),
    CONSTRAINT ck_dev_device_credential_topic CHECK (btrim(topic_root) <> ''),
    CONSTRAINT ck_dev_device_credential_status CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED')),
    CONSTRAINT ck_dev_device_credential_revoke CHECK (
        (status = 'REVOKED' AND revoked_at IS NOT NULL)
        OR (status <> 'REVOKED' AND revoked_at IS NULL)
    ),
    CONSTRAINT ck_dev_device_credential_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uk_dev_device_active_credential
    ON dev_device_credential (device_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_dev_device_credential_device
    ON dev_device_credential (device_id, status, created_at DESC);

COMMENT ON TABLE dev_product IS '设备产品定义';
COMMENT ON TABLE dev_device IS '矿区设备台账';
COMMENT ON TABLE dev_device_credential IS '设备 MQTT 凭据安全摘要';
