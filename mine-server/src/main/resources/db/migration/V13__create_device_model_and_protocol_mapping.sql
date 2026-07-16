-- 物模型和协议字段映射均以不可变发布版本驱动运行时，草稿修改不影响现场数据链路。
CREATE TABLE dev_model_version (
    id              UUID         NOT NULL,
    product_id      UUID         NOT NULL,
    version_number  INTEGER      NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
    description     VARCHAR(500),
    published_at    TIMESTAMPTZ,
    published_by    UUID,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      UUID,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by      UUID,
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_dev_model_version PRIMARY KEY (id),
    CONSTRAINT uk_dev_model_version_product_number UNIQUE (product_id, version_number),
    CONSTRAINT fk_dev_model_version_product FOREIGN KEY (product_id) REFERENCES dev_product (id),
    CONSTRAINT ck_dev_model_version_number CHECK (version_number > 0),
    CONSTRAINT ck_dev_model_version_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    CONSTRAINT ck_dev_model_version_publish CHECK (
        (status = 'DRAFT' AND published_at IS NULL AND published_by IS NULL)
        OR (status IN ('PUBLISHED', 'RETIRED') AND published_at IS NOT NULL)
    ),
    CONSTRAINT ck_dev_model_version_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uk_dev_model_version_published
    ON dev_model_version (product_id)
    WHERE status = 'PUBLISHED';

CREATE TABLE dev_model_field (
    id                UUID          NOT NULL,
    model_version_id  UUID          NOT NULL,
    identifier        VARCHAR(128)  NOT NULL,
    name_zh           VARCHAR(128)  NOT NULL,
    name_en           VARCHAR(128),
    data_type         VARCHAR(32)   NOT NULL,
    unit              VARCHAR(32),
    access_mode       VARCHAR(32)   NOT NULL DEFAULT 'READ',
    required_field    BOOLEAN       NOT NULL DEFAULT FALSE,
    minimum_value     NUMERIC(30, 10),
    maximum_value     NUMERIC(30, 10),
    decimal_places    INTEGER,
    sort_order        INTEGER       NOT NULL DEFAULT 0,
    description       VARCHAR(500),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by        UUID,
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by        UUID,
    version           BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_dev_model_field PRIMARY KEY (id),
    CONSTRAINT uk_dev_model_field_identifier UNIQUE (model_version_id, identifier),
    CONSTRAINT fk_dev_model_field_version FOREIGN KEY (model_version_id) REFERENCES dev_model_version (id),
    CONSTRAINT ck_dev_model_field_identifier CHECK (identifier ~ '^[a-z][A-Za-z0-9_]{1,127}$'),
    CONSTRAINT ck_dev_model_field_name CHECK (btrim(name_zh) <> ''),
    CONSTRAINT ck_dev_model_field_type CHECK (data_type IN ('INTEGER', 'DECIMAL', 'BOOLEAN', 'STRING', 'ENUM', 'DATETIME', 'GEO_POINT')),
    CONSTRAINT ck_dev_model_field_access CHECK (access_mode IN ('READ', 'WRITE', 'READ_WRITE')),
    CONSTRAINT ck_dev_model_field_range CHECK (minimum_value IS NULL OR maximum_value IS NULL OR minimum_value <= maximum_value),
    CONSTRAINT ck_dev_model_field_decimal CHECK (decimal_places IS NULL OR decimal_places BETWEEN 0 AND 10),
    CONSTRAINT ck_dev_model_field_sort CHECK (sort_order >= 0),
    CONSTRAINT ck_dev_model_field_version_number CHECK (version >= 0)
);

CREATE INDEX idx_dev_model_field_version_sort
    ON dev_model_field (model_version_id, sort_order, id);

CREATE TABLE dev_protocol_mapping_version (
    id                UUID         NOT NULL,
    product_id        UUID         NOT NULL,
    profile_key       VARCHAR(64)  NOT NULL,
    name              VARCHAR(128) NOT NULL,
    protocol_type     VARCHAR(32)  NOT NULL,
    message_type      VARCHAR(32)  NOT NULL,
    version_number    INTEGER      NOT NULL,
    status            VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
    topic_or_frame    VARCHAR(255),
    description       VARCHAR(500),
    published_at      TIMESTAMPTZ,
    published_by      UUID,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by        UUID,
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by        UUID,
    version           BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_dev_protocol_mapping_version PRIMARY KEY (id),
    CONSTRAINT uk_dev_protocol_mapping_product_number UNIQUE (product_id, profile_key, version_number),
    CONSTRAINT fk_dev_protocol_mapping_product FOREIGN KEY (product_id) REFERENCES dev_product (id),
    CONSTRAINT ck_dev_protocol_mapping_key CHECK (profile_key ~ '^[a-z][a-z0-9_-]{2,63}$'),
    CONSTRAINT ck_dev_protocol_mapping_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_dev_protocol_mapping_protocol CHECK (protocol_type IN ('MQTT_JSON', 'MQTT_BINARY', 'TCP_BINARY')),
    CONSTRAINT ck_dev_protocol_mapping_message CHECK (message_type IN ('TELEMETRY', 'STATE', 'EVENT', 'COMMAND_REPLY')),
    CONSTRAINT ck_dev_protocol_mapping_number CHECK (version_number > 0),
    CONSTRAINT ck_dev_protocol_mapping_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    CONSTRAINT ck_dev_protocol_mapping_publish CHECK (
        (status = 'DRAFT' AND published_at IS NULL AND published_by IS NULL)
        OR (status IN ('PUBLISHED', 'RETIRED') AND published_at IS NOT NULL)
    ),
    CONSTRAINT ck_dev_protocol_mapping_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uk_dev_protocol_mapping_published
    ON dev_protocol_mapping_version (product_id, profile_key)
    WHERE status = 'PUBLISHED';

CREATE TABLE dev_protocol_field_mapping (
    id                  UUID          NOT NULL,
    mapping_version_id  UUID          NOT NULL,
    source_path         VARCHAR(255)  NOT NULL,
    source_data_type    VARCHAR(32)   NOT NULL,
    target_identifier   VARCHAR(128)  NOT NULL,
    transform_type      VARCHAR(32)   NOT NULL DEFAULT 'NONE',
    scale_factor        NUMERIC(30, 10),
    offset_value        NUMERIC(30, 10),
    expression          VARCHAR(1000),
    required_field      BOOLEAN       NOT NULL DEFAULT FALSE,
    sort_order          INTEGER       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by          UUID,
    version             BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_dev_protocol_field_mapping PRIMARY KEY (id),
    CONSTRAINT uk_dev_protocol_field_source UNIQUE (mapping_version_id, source_path),
    CONSTRAINT fk_dev_protocol_field_version FOREIGN KEY (mapping_version_id) REFERENCES dev_protocol_mapping_version (id),
    CONSTRAINT ck_dev_protocol_field_source CHECK (btrim(source_path) <> ''),
    CONSTRAINT ck_dev_protocol_field_source_type CHECK (source_data_type IN ('INTEGER', 'DECIMAL', 'BOOLEAN', 'STRING', 'BINARY', 'DATETIME')),
    CONSTRAINT ck_dev_protocol_field_target CHECK (target_identifier ~ '^[a-z][A-Za-z0-9_]{1,127}$'),
    CONSTRAINT ck_dev_protocol_field_transform CHECK (transform_type IN ('NONE', 'SCALE', 'OFFSET', 'SCALE_OFFSET', 'ENUM', 'EXPRESSION')),
    CONSTRAINT ck_dev_protocol_field_expression CHECK (transform_type <> 'EXPRESSION' OR btrim(expression) <> ''),
    CONSTRAINT ck_dev_protocol_field_sort CHECK (sort_order >= 0),
    CONSTRAINT ck_dev_protocol_field_version_number CHECK (version >= 0)
);

CREATE INDEX idx_dev_protocol_field_mapping_sort
    ON dev_protocol_field_mapping (mapping_version_id, sort_order, id);

COMMENT ON TABLE dev_model_version IS '设备产品物模型版本';
COMMENT ON COLUMN dev_model_version.id IS '物模型版本 UUIDv7 标识';
COMMENT ON COLUMN dev_model_version.product_id IS '所属设备产品标识';
COMMENT ON COLUMN dev_model_version.version_number IS '产品内单调递增版本号';
COMMENT ON COLUMN dev_model_version.status IS '草稿、已发布或已退役状态';
COMMENT ON COLUMN dev_model_version.description IS '版本变更说明';
COMMENT ON COLUMN dev_model_version.published_at IS '首次发布时间';
COMMENT ON COLUMN dev_model_version.published_by IS '发布管理员标识';
COMMENT ON COLUMN dev_model_version.created_at IS '创建时间';
COMMENT ON COLUMN dev_model_version.created_by IS '创建人标识';
COMMENT ON COLUMN dev_model_version.updated_at IS '最后更新时间';
COMMENT ON COLUMN dev_model_version.updated_by IS '最后更新人标识';
COMMENT ON COLUMN dev_model_version.version IS '乐观锁版本';

COMMENT ON TABLE dev_model_field IS '物模型版本字段定义';
COMMENT ON COLUMN dev_model_field.id IS '字段 UUIDv7 标识';
COMMENT ON COLUMN dev_model_field.model_version_id IS '所属物模型版本标识';
COMMENT ON COLUMN dev_model_field.identifier IS '平台统一字段标识符';
COMMENT ON COLUMN dev_model_field.name_zh IS '字段中文名称';
COMMENT ON COLUMN dev_model_field.name_en IS '字段英文名称';
COMMENT ON COLUMN dev_model_field.data_type IS '平台统一数据类型';
COMMENT ON COLUMN dev_model_field.unit IS '工程单位';
COMMENT ON COLUMN dev_model_field.access_mode IS '读写访问模式';
COMMENT ON COLUMN dev_model_field.required_field IS '报文中是否必须存在';
COMMENT ON COLUMN dev_model_field.minimum_value IS '允许的最小数值';
COMMENT ON COLUMN dev_model_field.maximum_value IS '允许的最大数值';
COMMENT ON COLUMN dev_model_field.decimal_places IS '小数保留位数';
COMMENT ON COLUMN dev_model_field.sort_order IS '显示与解析顺序';
COMMENT ON COLUMN dev_model_field.description IS '字段用途说明';
COMMENT ON COLUMN dev_model_field.created_at IS '创建时间';
COMMENT ON COLUMN dev_model_field.created_by IS '创建人标识';
COMMENT ON COLUMN dev_model_field.updated_at IS '最后更新时间';
COMMENT ON COLUMN dev_model_field.updated_by IS '最后更新人标识';
COMMENT ON COLUMN dev_model_field.version IS '乐观锁版本';

COMMENT ON TABLE dev_protocol_mapping_version IS '设备产品协议字段映射版本';
COMMENT ON COLUMN dev_protocol_mapping_version.id IS '协议映射版本 UUIDv7 标识';
COMMENT ON COLUMN dev_protocol_mapping_version.product_id IS '所属设备产品标识';
COMMENT ON COLUMN dev_protocol_mapping_version.profile_key IS '产品内稳定协议配置编码';
COMMENT ON COLUMN dev_protocol_mapping_version.name IS '协议配置名称';
COMMENT ON COLUMN dev_protocol_mapping_version.protocol_type IS 'MQTT JSON、MQTT 二进制或 TCP 二进制协议类型';
COMMENT ON COLUMN dev_protocol_mapping_version.message_type IS '遥测、状态、事件或命令回复消息类型';
COMMENT ON COLUMN dev_protocol_mapping_version.version_number IS '同协议配置内单调递增版本号';
COMMENT ON COLUMN dev_protocol_mapping_version.status IS '草稿、已发布或已退役状态';
COMMENT ON COLUMN dev_protocol_mapping_version.topic_or_frame IS 'MQTT Topic 模板或 TCP 帧类型标识';
COMMENT ON COLUMN dev_protocol_mapping_version.description IS '版本变更说明';
COMMENT ON COLUMN dev_protocol_mapping_version.published_at IS '首次发布时间';
COMMENT ON COLUMN dev_protocol_mapping_version.published_by IS '发布管理员标识';
COMMENT ON COLUMN dev_protocol_mapping_version.created_at IS '创建时间';
COMMENT ON COLUMN dev_protocol_mapping_version.created_by IS '创建人标识';
COMMENT ON COLUMN dev_protocol_mapping_version.updated_at IS '最后更新时间';
COMMENT ON COLUMN dev_protocol_mapping_version.updated_by IS '最后更新人标识';
COMMENT ON COLUMN dev_protocol_mapping_version.version IS '乐观锁版本';

COMMENT ON TABLE dev_protocol_field_mapping IS '协议原始字段到物模型字段的映射规则';
COMMENT ON COLUMN dev_protocol_field_mapping.id IS '字段映射 UUIDv7 标识';
COMMENT ON COLUMN dev_protocol_field_mapping.mapping_version_id IS '所属协议映射版本标识';
COMMENT ON COLUMN dev_protocol_field_mapping.source_path IS 'JSON 路径、二进制字段名或寄存器路径';
COMMENT ON COLUMN dev_protocol_field_mapping.source_data_type IS '厂商报文原始数据类型';
COMMENT ON COLUMN dev_protocol_field_mapping.target_identifier IS '目标物模型统一字段标识符';
COMMENT ON COLUMN dev_protocol_field_mapping.transform_type IS '无转换、缩放、偏移、枚举或受限表达式转换';
COMMENT ON COLUMN dev_protocol_field_mapping.scale_factor IS '数值缩放系数';
COMMENT ON COLUMN dev_protocol_field_mapping.offset_value IS '数值偏移量';
COMMENT ON COLUMN dev_protocol_field_mapping.expression IS '受限转换表达式，不允许任意脚本';
COMMENT ON COLUMN dev_protocol_field_mapping.required_field IS '缺失时是否判定报文无效';
COMMENT ON COLUMN dev_protocol_field_mapping.sort_order IS '映射执行顺序';
COMMENT ON COLUMN dev_protocol_field_mapping.created_at IS '创建时间';
COMMENT ON COLUMN dev_protocol_field_mapping.created_by IS '创建人标识';
COMMENT ON COLUMN dev_protocol_field_mapping.updated_at IS '最后更新时间';
COMMENT ON COLUMN dev_protocol_field_mapping.updated_by IS '最后更新人标识';
COMMENT ON COLUMN dev_protocol_field_mapping.version IS '乐观锁版本';

UPDATE sys_menu SET i18n_key = 'mine.menu.device', protected_node = TRUE
WHERE id = '019b0000-0000-7000-8000-000000000200';
UPDATE sys_menu SET i18n_key = 'mine.menu.products', protected_node = TRUE
WHERE component_key = 'device-product-list';
UPDATE sys_menu SET i18n_key = 'mine.menu.devices', protected_node = TRUE
WHERE component_key = 'device-device-list';
UPDATE sys_menu SET i18n_key = 'mine.menu.monitor', protected_node = TRUE, sort_order = 40
WHERE component_key = 'device-monitor';

INSERT INTO sys_menu (
    id, parent_id, name, type, path, component_key, permission,
    icon, sort_order, visible, i18n_key, protected_node
) VALUES
    ('019b0000-0000-7000-8000-000000000240', '019b0000-0000-7000-8000-000000000200',
     '物模型与协议', 'MENU', '/device/models', 'device-model-management', 'device:model:view',
     'mdi:vector-polyline-edit', 30, TRUE, 'mine.menu.models', TRUE),
    ('019b0000-0000-7000-8000-000000000241', '019b0000-0000-7000-8000-000000000240',
     '编辑物模型与协议', 'BUTTON', NULL, NULL, 'device:model:edit', NULL, 10, FALSE, NULL, TRUE),
    ('019b0000-0000-7000-8000-000000000242', '019b0000-0000-7000-8000-000000000240',
     '发布物模型与协议', 'BUTTON', NULL, NULL, 'device:model:publish', NULL, 20, FALSE, NULL, TRUE);
