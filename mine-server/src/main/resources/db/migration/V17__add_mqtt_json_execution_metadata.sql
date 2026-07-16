-- MQTT JSON 元数据路径和枚举码表是运行时编译快照的一部分，不能藏在前端约定中。
ALTER TABLE dev_protocol_mapping_version
    ADD COLUMN metadata_paths JSONB NOT NULL DEFAULT '{
      "productKeyPath":"productKey",
      "deviceCodePath":"deviceCode",
      "bootIdPath":"bootId",
      "sequencePath":"sequence",
      "occurredAtPath":"occurredAt",
      "replayedPath":"replayed"
    }'::jsonb,
    ADD CONSTRAINT ck_dev_protocol_mapping_metadata_paths
        CHECK (jsonb_typeof(metadata_paths) = 'object');

ALTER TABLE dev_protocol_field_mapping
    ADD COLUMN enum_mapping JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD CONSTRAINT ck_dev_protocol_field_enum_mapping
        CHECK (jsonb_typeof(enum_mapping) = 'object'),
    ADD CONSTRAINT ck_dev_protocol_field_enum_required
        CHECK (transform_type <> 'ENUM' OR enum_mapping <> '{}'::jsonb);

COMMENT ON COLUMN dev_protocol_mapping_version.metadata_paths IS '产品、设备、启动批次、序号、发生时间和补传标记的受限点路径';
COMMENT ON COLUMN dev_protocol_field_mapping.enum_mapping IS '厂商枚举码到平台标准枚举值的有限映射';
