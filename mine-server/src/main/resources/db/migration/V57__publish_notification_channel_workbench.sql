-- 在告警管理下发布通知渠道工作台，并增加独立敏感配置查看权限。
UPDATE sys_menu
SET parent_id = '019b0000-0000-7000-8000-000000000400',
    name = '通知渠道',
    type = 'MENU',
    path = '/alarm/channels',
    component_key = 'notification-channel-management',
    icon = 'mdi:message-alert-outline',
    sort_order = 20,
    visible = TRUE,
    i18n_key = 'mine.menu.notificationChannels'
WHERE id = '019b0000-0000-7000-8000-000000000170'
  AND permission = 'notification:channel:view';

UPDATE sys_menu
SET parent_id = '019b0000-0000-7000-8000-000000000170',
    sort_order = 20
WHERE id = '019b0000-0000-7000-8000-000000000171'
  AND permission = 'notification:channel:edit';

INSERT INTO sys_menu (
    id, parent_id, name, type, path, component_key, permission,
    icon, sort_order, visible, i18n_key, protected_node
) VALUES
    ('019b0000-0000-7000-8000-000000000172', '019b0000-0000-7000-8000-000000000170',
     '查看通知渠道敏感配置', 'BUTTON', NULL, NULL,
     'notification:channel:secret:view', NULL, 30, FALSE, NULL, TRUE);
