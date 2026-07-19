-- 产品协议版本显式保存运行时路由和解析器绑定，避免按端口或 Payload 内容猜测协议。
ALTER TABLE dev_protocol_mapping_version
    ADD COLUMN parser_type VARCHAR(32) NOT NULL DEFAULT 'CONFIGURATION',
    ADD COLUMN plugin_key VARCHAR(64),
    ADD COLUMN plugin_version VARCHAR(128),
    ADD COLUMN routing_mode VARCHAR(32) NOT NULL DEFAULT 'TRUSTED_IDENTITY';

-- 历史二进制草稿尚未指定插件时允许继续编辑，但发布前必须由应用层补齐插件绑定。
UPDATE dev_protocol_mapping_version
SET parser_type = 'JAVA_PLUGIN',
    routing_mode = CASE
        WHEN protocol_type = 'TCP_BINARY' THEN 'LEGACY_LISTENER'
        ELSE 'TRUSTED_IDENTITY'
    END
WHERE protocol_type IN ('MQTT_BINARY', 'TCP_BINARY');

ALTER TABLE dev_protocol_mapping_version
    ADD CONSTRAINT ck_dev_protocol_mapping_parser_type
        CHECK (parser_type IN ('CONFIGURATION', 'JAVA_PLUGIN')),
    ADD CONSTRAINT ck_dev_protocol_mapping_routing_mode
        CHECK (routing_mode IN ('TRUSTED_IDENTITY', 'LEGACY_LISTENER')),
    ADD CONSTRAINT ck_dev_protocol_mapping_runtime_binding
        CHECK (
            (protocol_type = 'MQTT_JSON'
                AND parser_type = 'CONFIGURATION'
                AND routing_mode = 'TRUSTED_IDENTITY'
                AND plugin_key IS NULL
                AND plugin_version IS NULL)
            OR
            (protocol_type = 'MQTT_BINARY'
                AND parser_type = 'JAVA_PLUGIN'
                AND routing_mode = 'TRUSTED_IDENTITY'
                AND (status = 'DRAFT' OR plugin_key IS NOT NULL AND plugin_version IS NOT NULL))
            OR
            (protocol_type = 'TCP_BINARY'
                AND parser_type = 'JAVA_PLUGIN'
                AND (status = 'DRAFT' OR plugin_key IS NOT NULL AND plugin_version IS NOT NULL))
        );

COMMENT ON COLUMN dev_protocol_mapping_version.parser_type IS '配置化执行计划或离线审核 Java 插件解析器类型';
COMMENT ON COLUMN dev_protocol_mapping_version.plugin_key IS 'Java 协议插件全局唯一键，配置化解析时为空';
COMMENT ON COLUMN dev_protocol_mapping_version.plugin_version IS 'Java 协议插件不可变版本，配置化解析时为空';
COMMENT ON COLUMN dev_protocol_mapping_version.routing_mode IS '可信设备身份路由或兼容监听器显式绑定路由模式';
