-- 建立通知渠道、接收对象、模板和不可变升级策略发布控制面。

CREATE TABLE notification_channel (
    id                  UUID          NOT NULL,
    channel_code        VARCHAR(64)   NOT NULL,
    name                VARCHAR(128)  NOT NULL,
    channel_type        VARCHAR(24)   NOT NULL,
    provider            VARCHAR(64)   NOT NULL,
    status              VARCHAR(16)   NOT NULL DEFAULT 'DISABLED',
    configuration       JSONB         NOT NULL DEFAULT '{}'::JSONB,
    secret_ciphertext   VARCHAR(8192),
    connect_timeout_ms  INTEGER       NOT NULL DEFAULT 3000,
    read_timeout_ms     INTEGER       NOT NULL DEFAULT 5000,
    last_verified_at    TIMESTAMPTZ,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by          UUID,
    version             BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_notification_channel PRIMARY KEY (id),
    CONSTRAINT uk_notification_channel_code UNIQUE (channel_code),
    CONSTRAINT fk_notification_channel_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_notification_channel_updated_by
        FOREIGN KEY (updated_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT ck_notification_channel_code
        CHECK (channel_code ~ '^[a-z][a-z0-9-]{1,63}$'),
    CONSTRAINT ck_notification_channel_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_notification_channel_type CHECK (
        channel_type IN ('EMAIL', 'SMS', 'VOICE', 'ENTERPRISE_IM', 'WEBHOOK')
    ),
    CONSTRAINT ck_notification_channel_provider
        CHECK (provider ~ '^[a-z][a-z0-9-]{1,63}$'),
    CONSTRAINT ck_notification_channel_status
        CHECK (status IN ('ENABLED', 'DISABLED')),
    CONSTRAINT ck_notification_channel_configuration CHECK (
        jsonb_typeof(configuration) = 'object'
        AND octet_length(configuration::TEXT) <= 32768
    ),
    CONSTRAINT ck_notification_channel_secret CHECK (
        secret_ciphertext IS NULL OR btrim(secret_ciphertext) <> ''
    ),
    CONSTRAINT ck_notification_channel_timeout CHECK (
        connect_timeout_ms BETWEEN 100 AND 30000
        AND read_timeout_ms BETWEEN 100 AND 60000
    ),
    CONSTRAINT ck_notification_channel_verification CHECK (
        last_verified_at IS NULL OR last_verified_at >= created_at
    ),
    CONSTRAINT ck_notification_channel_audit
        CHECK (updated_at >= created_at AND version >= 0)
);

CREATE INDEX idx_notification_channel_type_status
    ON notification_channel(channel_type, status, provider, id);

CREATE TABLE notification_recipient_group (
    id           UUID          NOT NULL,
    group_code   VARCHAR(64)   NOT NULL,
    name         VARCHAR(128)  NOT NULL,
    description  VARCHAR(500),
    status       VARCHAR(16)   NOT NULL DEFAULT 'ENABLED',
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by   UUID,
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by   UUID,
    version      BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_notification_recipient_group PRIMARY KEY (id),
    CONSTRAINT uk_notification_recipient_group_code UNIQUE (group_code),
    CONSTRAINT fk_notification_recipient_group_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_notification_recipient_group_updated_by
        FOREIGN KEY (updated_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT ck_notification_recipient_group_code
        CHECK (group_code ~ '^[a-z][a-z0-9-]{1,63}$'),
    CONSTRAINT ck_notification_recipient_group_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_notification_recipient_group_description
        CHECK (description IS NULL OR btrim(description) <> ''),
    CONSTRAINT ck_notification_recipient_group_status
        CHECK (status IN ('ENABLED', 'DISABLED')),
    CONSTRAINT ck_notification_recipient_group_audit
        CHECK (updated_at >= created_at AND version >= 0)
);

CREATE INDEX idx_notification_recipient_group_status_name
    ON notification_recipient_group(status, name, id);

CREATE TABLE notification_recipient (
    id                 UUID          NOT NULL,
    group_id           UUID          NOT NULL,
    recipient_type     VARCHAR(24)   NOT NULL,
    user_id            UUID,
    display_name       VARCHAR(128)  NOT NULL,
    channel_type       VARCHAR(24)   NOT NULL,
    target_ciphertext  VARCHAR(8192) NOT NULL,
    target_digest      VARCHAR(71)   NOT NULL,
    target_masked      VARCHAR(256)  NOT NULL,
    status             VARCHAR(16)   NOT NULL DEFAULT 'ENABLED',
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by         UUID,
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by         UUID,
    version            BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_notification_recipient PRIMARY KEY (id),
    CONSTRAINT uk_notification_recipient_target
        UNIQUE (group_id, channel_type, target_digest),
    CONSTRAINT fk_notification_recipient_group
        FOREIGN KEY (group_id) REFERENCES notification_recipient_group(id),
    CONSTRAINT fk_notification_recipient_user
        FOREIGN KEY (user_id) REFERENCES sys_user(id),
    CONSTRAINT fk_notification_recipient_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_notification_recipient_updated_by
        FOREIGN KEY (updated_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT ck_notification_recipient_type CHECK (
        recipient_type IN ('SYSTEM_USER', 'EXTERNAL')
    ),
    CONSTRAINT ck_notification_recipient_user CHECK (
        (recipient_type = 'SYSTEM_USER' AND user_id IS NOT NULL)
        OR (recipient_type = 'EXTERNAL' AND user_id IS NULL)
    ),
    CONSTRAINT ck_notification_recipient_display_name
        CHECK (btrim(display_name) <> ''),
    CONSTRAINT ck_notification_recipient_channel_type CHECK (
        channel_type IN ('EMAIL', 'SMS', 'VOICE', 'ENTERPRISE_IM', 'WEBHOOK')
    ),
    CONSTRAINT ck_notification_recipient_target CHECK (
        btrim(target_ciphertext) <> ''
        AND target_digest ~ '^sha256:[0-9a-f]{64}$'
        AND btrim(target_masked) <> ''
    ),
    CONSTRAINT ck_notification_recipient_status
        CHECK (status IN ('ENABLED', 'DISABLED')),
    CONSTRAINT ck_notification_recipient_audit
        CHECK (updated_at >= created_at AND version >= 0)
);

CREATE INDEX idx_notification_recipient_group_status
    ON notification_recipient(group_id, status, channel_type, id);
CREATE INDEX idx_notification_recipient_user
    ON notification_recipient(user_id, status, id)
    WHERE user_id IS NOT NULL;

CREATE TABLE notification_template (
    id                      UUID          NOT NULL,
    template_code           VARCHAR(64)   NOT NULL,
    name                    VARCHAR(128)  NOT NULL,
    channel_type            VARCHAR(24)   NOT NULL,
    event_type              VARCHAR(24)   NOT NULL,
    locale                  VARCHAR(16)   NOT NULL DEFAULT 'zh-CN',
    subject_template        VARCHAR(1000),
    body_template           VARCHAR(16384) NOT NULL,
    provider_template_code  VARCHAR(128),
    allowed_variables       JSONB         NOT NULL DEFAULT '[]'::JSONB,
    status                  VARCHAR(16)   NOT NULL DEFAULT 'ENABLED',
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by              UUID,
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by              UUID,
    version                 BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_notification_template PRIMARY KEY (id),
    CONSTRAINT uk_notification_template_code UNIQUE (template_code),
    CONSTRAINT fk_notification_template_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_notification_template_updated_by
        FOREIGN KEY (updated_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT ck_notification_template_code
        CHECK (template_code ~ '^[a-z][a-z0-9-]{1,63}$'),
    CONSTRAINT ck_notification_template_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_notification_template_channel_type CHECK (
        channel_type IN ('EMAIL', 'SMS', 'VOICE', 'ENTERPRISE_IM', 'WEBHOOK')
    ),
    CONSTRAINT ck_notification_template_event_type CHECK (
        event_type IN ('ALARM_RAISED', 'ALARM_RECOVERED', 'TICKET_ESCALATED')
    ),
    CONSTRAINT ck_notification_template_locale
        CHECK (locale IN ('zh-CN', 'en-US')),
    CONSTRAINT ck_notification_template_content CHECK (
        (subject_template IS NULL OR btrim(subject_template) <> '')
        AND btrim(body_template) <> ''
        AND (provider_template_code IS NULL OR btrim(provider_template_code) <> '')
    ),
    CONSTRAINT ck_notification_template_variables CHECK (
        jsonb_typeof(allowed_variables) = 'array'
        AND octet_length(allowed_variables::TEXT) <= 8192
    ),
    CONSTRAINT ck_notification_template_status
        CHECK (status IN ('ENABLED', 'DISABLED')),
    CONSTRAINT ck_notification_template_audit
        CHECK (updated_at >= created_at AND version >= 0)
);

CREATE INDEX idx_notification_template_type_event_status
    ON notification_template(channel_type, event_type, status, locale, id);

CREATE TABLE notification_policy (
    id           UUID          NOT NULL,
    policy_code  VARCHAR(64)   NOT NULL,
    name         VARCHAR(128)  NOT NULL,
    description  VARCHAR(500),
    priority     INTEGER       NOT NULL DEFAULT 100,
    status       VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by   UUID,
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by   UUID,
    archived_at  TIMESTAMPTZ,
    archived_by  UUID,
    version      BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_notification_policy PRIMARY KEY (id),
    CONSTRAINT uk_notification_policy_code UNIQUE (policy_code),
    CONSTRAINT fk_notification_policy_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_notification_policy_updated_by
        FOREIGN KEY (updated_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_notification_policy_archived_by
        FOREIGN KEY (archived_by) REFERENCES sys_user(id),
    CONSTRAINT ck_notification_policy_code
        CHECK (policy_code ~ '^[a-z][a-z0-9-]{1,63}$'),
    CONSTRAINT ck_notification_policy_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_notification_policy_description
        CHECK (description IS NULL OR btrim(description) <> ''),
    CONSTRAINT ck_notification_policy_priority CHECK (priority BETWEEN 0 AND 1000),
    CONSTRAINT ck_notification_policy_status
        CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_notification_policy_lifecycle CHECK (
        (status = 'ACTIVE' AND archived_at IS NULL AND archived_by IS NULL)
        OR (status = 'ARCHIVED' AND archived_at IS NOT NULL AND archived_by IS NOT NULL
            AND archived_at >= created_at)
    ),
    CONSTRAINT ck_notification_policy_audit
        CHECK (updated_at >= created_at AND version >= 0)
);

CREATE INDEX idx_notification_policy_status_priority
    ON notification_policy(status, priority DESC, policy_code, id);

CREATE TABLE notification_policy_version (
    id                  UUID          NOT NULL,
    policy_id           UUID          NOT NULL,
    version_number      INTEGER       NOT NULL,
    state               VARCHAR(16)   NOT NULL DEFAULT 'DRAFT',
    match_conditions    JSONB         NOT NULL DEFAULT '{}'::JSONB,
    severity_filter     JSONB         NOT NULL,
    stop_on_ack         BOOLEAN       NOT NULL DEFAULT TRUE,
    notify_on_recovery  BOOLEAN       NOT NULL DEFAULT FALSE,
    published_at        TIMESTAMPTZ,
    published_by        UUID,
    publication_reason  VARCHAR(500),
    retired_at          TIMESTAMPTZ,
    retired_by          UUID,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    CONSTRAINT pk_notification_policy_version PRIMARY KEY (id),
    CONSTRAINT uk_notification_policy_version UNIQUE (policy_id, version_number),
    CONSTRAINT fk_notification_policy_version_policy
        FOREIGN KEY (policy_id) REFERENCES notification_policy(id),
    CONSTRAINT fk_notification_policy_version_published_by
        FOREIGN KEY (published_by) REFERENCES sys_user(id),
    CONSTRAINT fk_notification_policy_version_retired_by
        FOREIGN KEY (retired_by) REFERENCES sys_user(id),
    CONSTRAINT fk_notification_policy_version_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT ck_notification_policy_version_number CHECK (version_number >= 1),
    CONSTRAINT ck_notification_policy_version_state
        CHECK (state IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    CONSTRAINT ck_notification_policy_version_conditions CHECK (
        jsonb_typeof(match_conditions) = 'object'
        AND octet_length(match_conditions::TEXT) <= 32768
        AND jsonb_typeof(severity_filter) = 'array'
        AND jsonb_array_length(severity_filter) BETWEEN 1 AND 3
        AND severity_filter <@ '["INFO", "WARNING", "CRITICAL"]'::JSONB
    ),
    CONSTRAINT ck_notification_policy_version_publication CHECK (
        (state = 'DRAFT'
            AND published_at IS NULL AND published_by IS NULL
            AND publication_reason IS NULL
            AND retired_at IS NULL AND retired_by IS NULL)
        OR (state = 'PUBLISHED'
            AND published_at IS NOT NULL AND published_by IS NOT NULL
            AND publication_reason IS NOT NULL AND btrim(publication_reason) <> ''
            AND retired_at IS NULL AND retired_by IS NULL
            AND published_at >= created_at)
        OR (state = 'RETIRED'
            AND published_at IS NOT NULL AND published_by IS NOT NULL
            AND publication_reason IS NOT NULL AND btrim(publication_reason) <> ''
            AND retired_at IS NOT NULL AND retired_by IS NOT NULL
            AND retired_at >= published_at)
    )
);

CREATE UNIQUE INDEX uk_notification_policy_single_published
    ON notification_policy_version(policy_id)
    WHERE state = 'PUBLISHED';
CREATE INDEX idx_notification_policy_version_policy_state
    ON notification_policy_version(policy_id, state, version_number DESC, id);

CREATE TABLE notification_policy_step (
    id                  UUID          NOT NULL,
    policy_version_id   UUID          NOT NULL,
    step_order          INTEGER       NOT NULL,
    delay_seconds       INTEGER       NOT NULL DEFAULT 0,
    channel_id          UUID          NOT NULL,
    recipient_group_id  UUID          NOT NULL,
    template_id         UUID          NOT NULL,
    ticket_action       VARCHAR(16)   NOT NULL DEFAULT 'NONE',
    ticket_priority     SMALLINT,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_notification_policy_step PRIMARY KEY (id),
    CONSTRAINT uk_notification_policy_step_order
        UNIQUE (policy_version_id, step_order),
    CONSTRAINT fk_notification_policy_step_version
        FOREIGN KEY (policy_version_id) REFERENCES notification_policy_version(id),
    CONSTRAINT fk_notification_policy_step_channel
        FOREIGN KEY (channel_id) REFERENCES notification_channel(id),
    CONSTRAINT fk_notification_policy_step_recipient_group
        FOREIGN KEY (recipient_group_id) REFERENCES notification_recipient_group(id),
    CONSTRAINT fk_notification_policy_step_template
        FOREIGN KEY (template_id) REFERENCES notification_template(id),
    CONSTRAINT ck_notification_policy_step_order CHECK (step_order >= 0),
    CONSTRAINT ck_notification_policy_step_delay
        CHECK (delay_seconds BETWEEN 0 AND 604800),
    CONSTRAINT ck_notification_policy_step_ticket_action CHECK (
        ticket_action IN ('NONE', 'CREATE', 'ASSIGN', 'ESCALATE')
    ),
    CONSTRAINT ck_notification_policy_step_ticket_priority CHECK (
        (ticket_action IN ('NONE', 'ASSIGN') AND ticket_priority IS NULL)
        OR (ticket_action IN ('CREATE', 'ESCALATE') AND ticket_priority BETWEEN 1 AND 100)
    )
);

CREATE INDEX idx_notification_policy_step_dependencies
    ON notification_policy_step(channel_id, recipient_group_id, template_id, id);

COMMENT ON TABLE notification_channel IS '通知供应商渠道及其安全配置';
COMMENT ON COLUMN notification_channel.id IS '通知渠道唯一标识';
COMMENT ON COLUMN notification_channel.channel_code IS '通知渠道稳定小写业务编码';
COMMENT ON COLUMN notification_channel.name IS '通知渠道展示名称';
COMMENT ON COLUMN notification_channel.channel_type IS '邮件短信语音企业即时通讯或 Webhook 渠道类型';
COMMENT ON COLUMN notification_channel.provider IS '通知供应商适配器稳定编码';
COMMENT ON COLUMN notification_channel.status IS '通知渠道启用或禁用状态';
COMMENT ON COLUMN notification_channel.configuration IS '不含敏感字段的有界渠道配置';
COMMENT ON COLUMN notification_channel.secret_ciphertext IS '使用通知独立密钥加密的渠道凭据密文';
COMMENT ON COLUMN notification_channel.connect_timeout_ms IS '供应商连接超时毫秒数';
COMMENT ON COLUMN notification_channel.read_timeout_ms IS '供应商响应读取超时毫秒数';
COMMENT ON COLUMN notification_channel.last_verified_at IS '渠道配置最后验证成功时间';
COMMENT ON COLUMN notification_channel.created_at IS '通知渠道创建时间';
COMMENT ON COLUMN notification_channel.created_by IS '通知渠道创建用户标识';
COMMENT ON COLUMN notification_channel.updated_at IS '通知渠道最近更新时间';
COMMENT ON COLUMN notification_channel.updated_by IS '通知渠道最近更新用户标识';
COMMENT ON COLUMN notification_channel.version IS '通知渠道乐观锁版本号';

COMMENT ON TABLE notification_recipient_group IS '通知接收对象值班组';
COMMENT ON COLUMN notification_recipient_group.id IS '接收组唯一标识';
COMMENT ON COLUMN notification_recipient_group.group_code IS '接收组稳定小写业务编码';
COMMENT ON COLUMN notification_recipient_group.name IS '接收组展示名称';
COMMENT ON COLUMN notification_recipient_group.description IS '接收组职责说明';
COMMENT ON COLUMN notification_recipient_group.status IS '接收组启用或禁用状态';
COMMENT ON COLUMN notification_recipient_group.created_at IS '接收组创建时间';
COMMENT ON COLUMN notification_recipient_group.created_by IS '接收组创建用户标识';
COMMENT ON COLUMN notification_recipient_group.updated_at IS '接收组最近更新时间';
COMMENT ON COLUMN notification_recipient_group.updated_by IS '接收组最近更新用户标识';
COMMENT ON COLUMN notification_recipient_group.version IS '接收组乐观锁版本号';

COMMENT ON TABLE notification_recipient IS '接收组内按渠道配置的加密通知目标';
COMMENT ON COLUMN notification_recipient.id IS '通知接收对象唯一标识';
COMMENT ON COLUMN notification_recipient.group_id IS '所属通知接收组标识';
COMMENT ON COLUMN notification_recipient.recipient_type IS '系统用户或外部联系人类型';
COMMENT ON COLUMN notification_recipient.user_id IS '系统用户标识，外部联系人为空';
COMMENT ON COLUMN notification_recipient.display_name IS '通知接收对象展示名称';
COMMENT ON COLUMN notification_recipient.channel_type IS '接收目标适用通知渠道类型';
COMMENT ON COLUMN notification_recipient.target_ciphertext IS '使用通知独立密钥加密的手机号邮箱或机器人地址密文';
COMMENT ON COLUMN notification_recipient.target_digest IS '用于组内去重且不可逆的接收目标摘要';
COMMENT ON COLUMN notification_recipient.target_masked IS '接收目标脱敏展示值';
COMMENT ON COLUMN notification_recipient.status IS '接收对象启用或禁用状态';
COMMENT ON COLUMN notification_recipient.created_at IS '接收对象创建时间';
COMMENT ON COLUMN notification_recipient.created_by IS '接收对象创建用户标识';
COMMENT ON COLUMN notification_recipient.updated_at IS '接收对象最近更新时间';
COMMENT ON COLUMN notification_recipient.updated_by IS '接收对象最近更新用户标识';
COMMENT ON COLUMN notification_recipient.version IS '接收对象乐观锁版本号';

COMMENT ON TABLE notification_template IS '按渠道和告警生命周期配置的受控通知模板';
COMMENT ON COLUMN notification_template.id IS '通知模板唯一标识';
COMMENT ON COLUMN notification_template.template_code IS '通知模板稳定小写业务编码';
COMMENT ON COLUMN notification_template.name IS '通知模板展示名称';
COMMENT ON COLUMN notification_template.channel_type IS '通知模板适用渠道类型';
COMMENT ON COLUMN notification_template.event_type IS '通知模板适用告警或事件单生命周期类型';
COMMENT ON COLUMN notification_template.locale IS '通知模板语言区域编码';
COMMENT ON COLUMN notification_template.subject_template IS '通知标题模板，无标题渠道可为空';
COMMENT ON COLUMN notification_template.body_template IS '通知正文或供应商模板参数表达式';
COMMENT ON COLUMN notification_template.provider_template_code IS '短信或语音供应商侧模板编码';
COMMENT ON COLUMN notification_template.allowed_variables IS '模板允许引用的固定变量白名单';
COMMENT ON COLUMN notification_template.status IS '通知模板启用或禁用状态';
COMMENT ON COLUMN notification_template.created_at IS '通知模板创建时间';
COMMENT ON COLUMN notification_template.created_by IS '通知模板创建用户标识';
COMMENT ON COLUMN notification_template.updated_at IS '通知模板最近更新时间';
COMMENT ON COLUMN notification_template.updated_by IS '通知模板最近更新用户标识';
COMMENT ON COLUMN notification_template.version IS '通知模板乐观锁版本号';

COMMENT ON TABLE notification_policy IS '告警通知升级策略稳定聚合根';
COMMENT ON COLUMN notification_policy.id IS '通知升级策略唯一标识';
COMMENT ON COLUMN notification_policy.policy_code IS '通知升级策略稳定小写业务编码';
COMMENT ON COLUMN notification_policy.name IS '通知升级策略展示名称';
COMMENT ON COLUMN notification_policy.description IS '通知升级策略用途说明';
COMMENT ON COLUMN notification_policy.priority IS '策略匹配优先级，数值越大优先级越高';
COMMENT ON COLUMN notification_policy.status IS '策略活动或归档状态';
COMMENT ON COLUMN notification_policy.created_at IS '通知升级策略创建时间';
COMMENT ON COLUMN notification_policy.created_by IS '通知升级策略创建用户标识';
COMMENT ON COLUMN notification_policy.updated_at IS '通知升级策略最近更新时间';
COMMENT ON COLUMN notification_policy.updated_by IS '通知升级策略最近更新用户标识';
COMMENT ON COLUMN notification_policy.archived_at IS '通知升级策略归档时间';
COMMENT ON COLUMN notification_policy.archived_by IS '执行策略归档的用户标识';
COMMENT ON COLUMN notification_policy.version IS '通知升级策略乐观锁版本号';

COMMENT ON TABLE notification_policy_version IS '不可变的通知升级策略草稿发布版本';
COMMENT ON COLUMN notification_policy_version.id IS '通知策略版本唯一标识';
COMMENT ON COLUMN notification_policy_version.policy_id IS '所属通知升级策略标识';
COMMENT ON COLUMN notification_policy_version.version_number IS '策略内单调递增版本号';
COMMENT ON COLUMN notification_policy_version.state IS '草稿已发布或已退役状态';
COMMENT ON COLUMN notification_policy_version.match_conditions IS '矿区规则来源等有界匹配条件';
COMMENT ON COLUMN notification_policy_version.severity_filter IS '允许匹配的告警等级数组';
COMMENT ON COLUMN notification_policy_version.stop_on_ack IS '告警确认后是否停止未来升级';
COMMENT ON COLUMN notification_policy_version.notify_on_recovery IS '告警恢复时是否发送恢复通知';
COMMENT ON COLUMN notification_policy_version.published_at IS '策略版本发布时间';
COMMENT ON COLUMN notification_policy_version.published_by IS '执行策略版本发布的用户标识';
COMMENT ON COLUMN notification_policy_version.publication_reason IS '策略版本发布原因';
COMMENT ON COLUMN notification_policy_version.retired_at IS '策略版本退役时间';
COMMENT ON COLUMN notification_policy_version.retired_by IS '执行策略版本退役的用户标识';
COMMENT ON COLUMN notification_policy_version.created_at IS '策略版本创建时间';
COMMENT ON COLUMN notification_policy_version.created_by IS '策略版本创建用户标识';

COMMENT ON TABLE notification_policy_step IS '通知升级策略版本的有序触达步骤';
COMMENT ON COLUMN notification_policy_step.id IS '通知升级步骤唯一标识';
COMMENT ON COLUMN notification_policy_step.policy_version_id IS '所属不可变策略版本标识';
COMMENT ON COLUMN notification_policy_step.step_order IS '从零开始且策略版本内唯一的步骤顺序';
COMMENT ON COLUMN notification_policy_step.delay_seconds IS '相对告警触发时间的升级延迟秒数';
COMMENT ON COLUMN notification_policy_step.channel_id IS '步骤使用的通知渠道标识';
COMMENT ON COLUMN notification_policy_step.recipient_group_id IS '步骤触达的接收组标识';
COMMENT ON COLUMN notification_policy_step.template_id IS '步骤使用的通知模板标识';
COMMENT ON COLUMN notification_policy_step.ticket_action IS '步骤触发的事件单创建分派或升级动作';
COMMENT ON COLUMN notification_policy_step.ticket_priority IS '创建或升级事件单时使用的优先级';
COMMENT ON COLUMN notification_policy_step.created_at IS '通知升级步骤创建时间';
