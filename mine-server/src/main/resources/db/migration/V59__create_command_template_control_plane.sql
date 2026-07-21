-- 命令模板采用不可变发布版本；原始十六进制下发必须绑定产品并固化执行快照。
CREATE TABLE cmd_template (
    id           UUID         NOT NULL,
    template_key VARCHAR(128) NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_by   UUID         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by   UUID         NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version      BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_cmd_template PRIMARY KEY (id),
    CONSTRAINT uk_cmd_template_key UNIQUE (template_key),
    CONSTRAINT fk_cmd_template_created_by FOREIGN KEY (created_by) REFERENCES sys_user (id),
    CONSTRAINT fk_cmd_template_updated_by FOREIGN KEY (updated_by) REFERENCES sys_user (id),
    CONSTRAINT ck_cmd_template_key CHECK (template_key ~ '^[A-Z][A-Z0-9_]{2,127}$'),
    CONSTRAINT ck_cmd_template_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_cmd_template_version CHECK (version >= 0)
);

CREATE TABLE cmd_template_version (
    id                      UUID         NOT NULL,
    template_id             UUID         NOT NULL,
    version_number          INTEGER      NOT NULL,
    status                  VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    name_zh                 VARCHAR(128) NOT NULL,
    name_en                 VARCHAR(128),
    description             VARCHAR(1000),
    risk_level              VARCHAR(16)  NOT NULL DEFAULT 'LOW',
    payload_format          VARCHAR(32)  NOT NULL,
    parameter_schema        JSONB        NOT NULL DEFAULT '{"type":"object","additionalProperties":false,"properties":{}}'::JSONB,
    raw_payload             BYTEA,
    raw_payload_hex         TEXT,
    raw_payload_sha256      CHAR(64),
    default_ttl_seconds     INTEGER      NOT NULL DEFAULT 300,
    default_timeout_seconds INTEGER      NOT NULL DEFAULT 30,
    requires_reason         BOOLEAN      NOT NULL DEFAULT TRUE,
    reply_expected          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by              UUID         NOT NULL,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by              UUID         NOT NULL,
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_by            UUID,
    published_at            TIMESTAMPTZ,
    version                 BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_cmd_template_version PRIMARY KEY (id),
    CONSTRAINT uk_cmd_template_version_number UNIQUE (template_id, version_number),
    CONSTRAINT fk_cmd_template_version_template FOREIGN KEY (template_id)
        REFERENCES cmd_template (id) ON DELETE CASCADE,
    CONSTRAINT fk_cmd_template_version_created_by FOREIGN KEY (created_by) REFERENCES sys_user (id),
    CONSTRAINT fk_cmd_template_version_updated_by FOREIGN KEY (updated_by) REFERENCES sys_user (id),
    CONSTRAINT fk_cmd_template_version_published_by FOREIGN KEY (published_by) REFERENCES sys_user (id),
    CONSTRAINT ck_cmd_template_version_number CHECK (version_number > 0),
    CONSTRAINT ck_cmd_template_version_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    CONSTRAINT ck_cmd_template_version_name_zh CHECK (btrim(name_zh) <> ''),
    CONSTRAINT ck_cmd_template_version_name_en CHECK (name_en IS NULL OR btrim(name_en) <> ''),
    CONSTRAINT ck_cmd_template_version_description CHECK (description IS NULL OR btrim(description) <> ''),
    CONSTRAINT ck_cmd_template_version_risk CHECK (risk_level = 'LOW'),
    CONSTRAINT ck_cmd_template_version_payload_format CHECK (payload_format IN ('MQTT_JSON', 'RAW_HEX')),
    CONSTRAINT ck_cmd_template_version_schema CHECK (jsonb_typeof(parameter_schema) = 'object'),
    CONSTRAINT ck_cmd_template_version_raw_payload CHECK (
        (payload_format = 'MQTT_JSON'
            AND raw_payload IS NULL
            AND raw_payload_hex IS NULL
            AND raw_payload_sha256 IS NULL
            AND reply_expected)
        OR
        (payload_format = 'RAW_HEX'
            AND raw_payload IS NOT NULL
            AND octet_length(raw_payload) BETWEEN 1 AND 4096
            AND raw_payload_hex ~ '^[0-9A-F]+$'
            AND length(raw_payload_hex) = octet_length(raw_payload) * 2
            AND raw_payload_hex = upper(encode(raw_payload, 'hex'))
            AND raw_payload_sha256 ~ '^[0-9a-f]{64}$'
            AND NOT reply_expected)
    ),
    CONSTRAINT ck_cmd_template_version_ttl CHECK (default_ttl_seconds BETWEEN 1 AND 86400),
    CONSTRAINT ck_cmd_template_version_timeout CHECK (
        default_timeout_seconds BETWEEN 1 AND default_ttl_seconds
    ),
    CONSTRAINT ck_cmd_template_version_publish CHECK (
        (status = 'DRAFT' AND published_by IS NULL AND published_at IS NULL)
        OR (status IN ('PUBLISHED', 'RETIRED') AND published_by IS NOT NULL AND published_at IS NOT NULL)
    ),
    CONSTRAINT ck_cmd_template_version_lock CHECK (version >= 0)
);

CREATE UNIQUE INDEX uk_cmd_template_one_draft
    ON cmd_template_version (template_id)
    WHERE status = 'DRAFT';

CREATE UNIQUE INDEX uk_cmd_template_one_published
    ON cmd_template_version (template_id)
    WHERE status = 'PUBLISHED';

CREATE INDEX idx_cmd_template_version_status
    ON cmd_template_version (status, updated_at DESC, id);

CREATE TABLE cmd_template_binding (
    id                  UUID        NOT NULL,
    template_version_id UUID        NOT NULL,
    product_id          UUID        NOT NULL,
    created_by          UUID        NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_cmd_template_binding PRIMARY KEY (id),
    CONSTRAINT uk_cmd_template_binding_product UNIQUE (template_version_id, product_id),
    CONSTRAINT fk_cmd_template_binding_version FOREIGN KEY (template_version_id)
        REFERENCES cmd_template_version (id) ON DELETE CASCADE,
    CONSTRAINT fk_cmd_template_binding_product FOREIGN KEY (product_id)
        REFERENCES dev_product (id),
    CONSTRAINT fk_cmd_template_binding_created_by FOREIGN KEY (created_by)
        REFERENCES sys_user (id)
);

CREATE INDEX idx_cmd_template_binding_product
    ON cmd_template_binding (product_id, template_version_id);

ALTER TABLE cmd_instance
    ADD COLUMN dispatch_source VARCHAR(32) NOT NULL DEFAULT 'BUILTIN_TEMPLATE',
    ADD COLUMN payload_format VARCHAR(32) NOT NULL DEFAULT 'MQTT_JSON',
    ADD COLUMN raw_payload BYTEA,
    ADD COLUMN raw_payload_hex TEXT,
    ADD COLUMN payload_sha256 CHAR(64),
    ADD COLUMN reply_expected BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE cmd_instance
    DROP CONSTRAINT ck_cmd_instance_status,
    ADD CONSTRAINT ck_cmd_instance_status CHECK (
        status IN (
            'CREATED', 'QUEUED', 'SENT', 'PUBLISHED', 'DEVICE_RECEIVED',
            'SUCCEEDED', 'REFUSED', 'FAILED', 'EXPIRED', 'TIMEOUT', 'CANCELLED'
        )
    ),
    ADD CONSTRAINT ck_cmd_instance_dispatch_source CHECK (
        dispatch_source IN ('BUILTIN_TEMPLATE', 'CUSTOM_TEMPLATE', 'AD_HOC_HEX')
    ),
    ADD CONSTRAINT ck_cmd_instance_payload_format CHECK (
        payload_format IN ('MQTT_JSON', 'RAW_HEX')
    ),
    ADD CONSTRAINT ck_cmd_instance_raw_payload CHECK (
        (payload_format = 'MQTT_JSON'
            AND raw_payload IS NULL
            AND raw_payload_hex IS NULL
            AND payload_sha256 IS NULL
            AND reply_expected)
        OR
        (payload_format = 'RAW_HEX'
            AND raw_payload IS NOT NULL
            AND octet_length(raw_payload) BETWEEN 1 AND 4096
            AND raw_payload_hex ~ '^[0-9A-F]+$'
            AND length(raw_payload_hex) = octet_length(raw_payload) * 2
            AND raw_payload_hex = upper(encode(raw_payload, 'hex'))
            AND payload_sha256 ~ '^[0-9a-f]{64}$'
            AND NOT reply_expected)
    );

COMMENT ON TABLE cmd_template IS '自定义设备命令模板稳定标识';
COMMENT ON COLUMN cmd_template.id IS '命令模板 UUIDv7 标识';
COMMENT ON COLUMN cmd_template.template_key IS '发布后保持稳定的大写命令模板编码';
COMMENT ON COLUMN cmd_template.status IS '命令模板启用或归档状态';
COMMENT ON COLUMN cmd_template.created_by IS '命令模板创建人标识';
COMMENT ON COLUMN cmd_template.created_at IS '命令模板创建时间';
COMMENT ON COLUMN cmd_template.updated_by IS '命令模板最后修改人标识';
COMMENT ON COLUMN cmd_template.updated_at IS '命令模板最后修改时间';
COMMENT ON COLUMN cmd_template.version IS '命令模板乐观锁版本';

COMMENT ON TABLE cmd_template_version IS '不可变发布的命令模板版本及载荷定义';
COMMENT ON COLUMN cmd_template_version.id IS '命令模板版本 UUIDv7 标识';
COMMENT ON COLUMN cmd_template_version.template_id IS '所属稳定命令模板标识';
COMMENT ON COLUMN cmd_template_version.version_number IS '模板内单调递增版本号';
COMMENT ON COLUMN cmd_template_version.status IS '草稿、已发布或已退役状态';
COMMENT ON COLUMN cmd_template_version.name_zh IS '命令模板中文名称';
COMMENT ON COLUMN cmd_template_version.name_en IS '命令模板英文名称';
COMMENT ON COLUMN cmd_template_version.description IS '命令用途和现场注意事项';
COMMENT ON COLUMN cmd_template_version.risk_level IS '第一版固定为低风险等级';
COMMENT ON COLUMN cmd_template_version.payload_format IS 'MQTT JSON 或原始十六进制载荷格式';
COMMENT ON COLUMN cmd_template_version.parameter_schema IS '受限 JSON Schema 参数定义';
COMMENT ON COLUMN cmd_template_version.raw_payload IS '固定原始十六进制命令字节';
COMMENT ON COLUMN cmd_template_version.raw_payload_hex IS '固定原始字节的大写十六进制表示';
COMMENT ON COLUMN cmd_template_version.raw_payload_sha256 IS '固定原始字节的 SHA-256 摘要';
COMMENT ON COLUMN cmd_template_version.default_ttl_seconds IS '命令默认排队存活秒数';
COMMENT ON COLUMN cmd_template_version.default_timeout_seconds IS '命令默认设备回复超时秒数';
COMMENT ON COLUMN cmd_template_version.requires_reason IS '下发时是否强制填写现场原因';
COMMENT ON COLUMN cmd_template_version.reply_expected IS '是否等待设备业务回复';
COMMENT ON COLUMN cmd_template_version.created_by IS '模板版本创建人标识';
COMMENT ON COLUMN cmd_template_version.created_at IS '模板版本创建时间';
COMMENT ON COLUMN cmd_template_version.updated_by IS '模板草稿最后修改人标识';
COMMENT ON COLUMN cmd_template_version.updated_at IS '模板草稿最后修改时间';
COMMENT ON COLUMN cmd_template_version.published_by IS '模板版本发布人标识';
COMMENT ON COLUMN cmd_template_version.published_at IS '模板版本发布时间';
COMMENT ON COLUMN cmd_template_version.version IS '模板草稿乐观锁版本';

COMMENT ON TABLE cmd_template_binding IS '命令模板版本与可用设备产品绑定';
COMMENT ON COLUMN cmd_template_binding.id IS '产品绑定 UUIDv7 标识';
COMMENT ON COLUMN cmd_template_binding.template_version_id IS '被绑定的命令模板版本标识';
COMMENT ON COLUMN cmd_template_binding.product_id IS '允许使用模板的设备产品标识';
COMMENT ON COLUMN cmd_template_binding.created_by IS '产品绑定创建人标识';
COMMENT ON COLUMN cmd_template_binding.created_at IS '产品绑定创建时间';

COMMENT ON COLUMN cmd_instance.dispatch_source IS '内置模板、自定义模板或临时十六进制来源';
COMMENT ON COLUMN cmd_instance.payload_format IS 'MQTT JSON 或原始十六进制执行载荷格式';
COMMENT ON COLUMN cmd_instance.raw_payload IS '本次执行固化的原始命令字节';
COMMENT ON COLUMN cmd_instance.raw_payload_hex IS '本次原始字节的大写十六进制表示';
COMMENT ON COLUMN cmd_instance.payload_sha256 IS '本次原始字节的 SHA-256 摘要';
COMMENT ON COLUMN cmd_instance.reply_expected IS '本次执行是否等待设备业务回复';

UPDATE sys_menu
SET parent_id = '019b0000-0000-7000-8000-000000000300',
    name = '命令模板',
    type = 'MENU',
    path = '/command/templates',
    component_key = 'command-templates',
    icon = 'mdi:code-json',
    sort_order = 20,
    visible = TRUE,
    i18n_key = 'mine.menu.commandTemplates',
    protected_node = TRUE,
    updated_at = CURRENT_TIMESTAMP,
    version = version + 1
WHERE id = '019b0000-0000-7000-8000-000000000311'
  AND permission = 'command:template:view';

INSERT INTO sys_menu (
    id, parent_id, name, type, path, component_key, permission,
    icon, sort_order, visible, i18n_key, protected_node
) VALUES
    ('019b0000-0000-7000-8000-000000000321', '019b0000-0000-7000-8000-000000000311',
     '编辑命令模板', 'BUTTON', NULL, NULL,
     'command:template:edit', NULL, 10, FALSE, NULL, TRUE),
    ('019b0000-0000-7000-8000-000000000322', '019b0000-0000-7000-8000-000000000311',
     '发布命令模板', 'BUTTON', NULL, NULL,
     'command:template:publish', NULL, 20, FALSE, NULL, TRUE),
    ('019b0000-0000-7000-8000-000000000314', '019b0000-0000-7000-8000-000000000310',
     '下发临时十六进制命令', 'BUTTON', NULL, NULL,
     'command:raw:execute', NULL, 40, FALSE, NULL, TRUE);
