-- 在既有通知渠道工作台下发布接收对象、模板和升级策略细粒度权限。
INSERT INTO sys_menu (
    id, parent_id, name, type, path, component_key, permission,
    icon, sort_order, visible, i18n_key, protected_node
) VALUES
    ('019b0000-0000-7000-8000-000000000173', '019b0000-0000-7000-8000-000000000170',
     '查看通知接收对象', 'BUTTON', NULL, NULL, 'notification:recipient:view',
     NULL, 40, FALSE, NULL, TRUE),
    ('019b0000-0000-7000-8000-000000000174', '019b0000-0000-7000-8000-000000000170',
     '编辑通知接收对象', 'BUTTON', NULL, NULL, 'notification:recipient:edit',
     NULL, 50, FALSE, NULL, TRUE),
    ('019b0000-0000-7000-8000-000000000175', '019b0000-0000-7000-8000-000000000170',
     '查看通知接收目标明文', 'BUTTON', NULL, NULL, 'notification:recipient:secret:view',
     NULL, 60, FALSE, NULL, TRUE),
    ('019b0000-0000-7000-8000-000000000176', '019b0000-0000-7000-8000-000000000170',
     '查看通知模板', 'BUTTON', NULL, NULL, 'notification:template:view',
     NULL, 70, FALSE, NULL, TRUE),
    ('019b0000-0000-7000-8000-000000000177', '019b0000-0000-7000-8000-000000000170',
     '编辑通知模板', 'BUTTON', NULL, NULL, 'notification:template:edit',
     NULL, 80, FALSE, NULL, TRUE),
    ('019b0000-0000-7000-8000-000000000178', '019b0000-0000-7000-8000-000000000170',
     '查看通知升级策略', 'BUTTON', NULL, NULL, 'notification:policy:view',
     NULL, 90, FALSE, NULL, TRUE),
    ('019b0000-0000-7000-8000-000000000179', '019b0000-0000-7000-8000-000000000170',
     '编辑通知升级策略', 'BUTTON', NULL, NULL, 'notification:policy:edit',
     NULL, 100, FALSE, NULL, TRUE),
    ('019b0000-0000-7000-8000-00000000017a', '019b0000-0000-7000-8000-000000000170',
     '发布通知升级策略', 'BUTTON', NULL, NULL, 'notification:policy:publish',
     NULL, 110, FALSE, NULL, TRUE);

-- 仅将既有通知职责平移到同等级能力，策略发布权继续由管理员显式授予。
INSERT INTO sys_role_menu (id, role_id, menu_id, created_at, created_by)
SELECT uuidv7(), granted.role_id, mapping.target_menu_id,
       CURRENT_TIMESTAMP, granted.created_by
FROM sys_role_menu granted
JOIN (VALUES
    ('019b0000-0000-7000-8000-000000000170'::UUID,
     '019b0000-0000-7000-8000-000000000173'::UUID),
    ('019b0000-0000-7000-8000-000000000170'::UUID,
     '019b0000-0000-7000-8000-000000000176'::UUID),
    ('019b0000-0000-7000-8000-000000000170'::UUID,
     '019b0000-0000-7000-8000-000000000178'::UUID),
    ('019b0000-0000-7000-8000-000000000171'::UUID,
     '019b0000-0000-7000-8000-000000000174'::UUID),
    ('019b0000-0000-7000-8000-000000000171'::UUID,
     '019b0000-0000-7000-8000-000000000177'::UUID),
    ('019b0000-0000-7000-8000-000000000171'::UUID,
     '019b0000-0000-7000-8000-000000000179'::UUID),
    ('019b0000-0000-7000-8000-000000000172'::UUID,
     '019b0000-0000-7000-8000-000000000175'::UUID)
) AS mapping(source_menu_id, target_menu_id)
  ON mapping.source_menu_id = granted.menu_id
ON CONFLICT (role_id, menu_id) DO NOTHING;
