-- 对齐通知接收对象类型与已发布 OpenAPI 契约，并原位升级既有外部联系人数据。
ALTER TABLE notification_recipient
    DROP CONSTRAINT ck_notification_recipient_user,
    DROP CONSTRAINT ck_notification_recipient_type;

UPDATE notification_recipient
SET recipient_type = 'EXTERNAL_CONTACT'
WHERE recipient_type = 'EXTERNAL';

ALTER TABLE notification_recipient
    ADD CONSTRAINT ck_notification_recipient_type CHECK (
        recipient_type IN ('SYSTEM_USER', 'EXTERNAL_CONTACT')
    ),
    ADD CONSTRAINT ck_notification_recipient_user CHECK (
        (recipient_type = 'SYSTEM_USER' AND user_id IS NOT NULL)
        OR (recipient_type = 'EXTERNAL_CONTACT' AND user_id IS NULL)
    );

COMMENT ON COLUMN notification_recipient.recipient_type
    IS '接收对象来源类型：平台用户或外部联系人';
