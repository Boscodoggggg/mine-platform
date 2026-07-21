-- 为开启恢复通知的升级步骤提供独立恢复模板，避免复用告警触发正文。
ALTER TABLE notification_policy_step
    ADD COLUMN recovery_template_id UUID,
    ADD CONSTRAINT fk_notification_policy_step_recovery_template
        FOREIGN KEY (recovery_template_id) REFERENCES notification_template(id);

COMMENT ON COLUMN notification_policy_step.recovery_template_id
    IS '开启恢复通知时使用的同渠道告警恢复模板标识';
