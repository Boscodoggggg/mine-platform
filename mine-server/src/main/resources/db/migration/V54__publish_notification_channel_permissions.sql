-- 发布通知渠道配置权限节点；页面发布前保持为系统管理下的隐藏按钮，普通角色按职责授权。
INSERT INTO sys_menu (
    id, parent_id, name, type, path, component_key, permission,
    icon, sort_order, visible, i18n_key, protected_node
) VALUES
    ('019b0000-0000-7000-8000-000000000170', '019b0000-0000-7000-8000-000000000100',
     '查看通知渠道', 'BUTTON', NULL, NULL, 'notification:channel:view',
     NULL, 60, FALSE, NULL, TRUE),
    ('019b0000-0000-7000-8000-000000000171', '019b0000-0000-7000-8000-000000000100',
     '编辑通知渠道', 'BUTTON', NULL, NULL, 'notification:channel:edit',
     NULL, 70, FALSE, NULL, TRUE);
