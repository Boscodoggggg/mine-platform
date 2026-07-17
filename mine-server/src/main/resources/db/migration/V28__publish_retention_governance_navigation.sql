-- 将数据保留从通用策略类型提升为数据治理域的独立可见入口。
INSERT INTO sys_menu (
    id, parent_id, name, type, path, component_key, permission,
    icon, sort_order, visible, i18n_key, protected_node
) VALUES
    ('019b0000-0000-7000-8000-000000000500', NULL,
     '数据治理', 'DIRECTORY', '/governance', NULL, NULL,
     'mdi:database-cog-outline', 175, TRUE, 'mine.menu.governance', TRUE),
    ('019b0000-0000-7000-8000-000000000510', '019b0000-0000-7000-8000-000000000500',
     '数据保留', 'MENU', '/governance/retention', 'retention-governance', 'governance:retention:view',
     'mdi:database-clock-outline', 10, TRUE, 'mine.menu.retention', TRUE);

-- 新入口不扩大写权限，并为既有策略查看角色补齐页面运行所需的最小读取依赖。
INSERT INTO sys_role_menu (id, role_id, menu_id, created_at, created_by)
SELECT uuidv7(),
       granted.role_id,
       required.menu_id,
       CURRENT_TIMESTAMP,
       granted.created_by
FROM sys_role_menu granted
CROSS JOIN (VALUES
    ('019b0000-0000-7000-8000-000000000210'::UUID),
    ('019b0000-0000-7000-8000-000000000220'::UUID),
    ('019b0000-0000-7000-8000-000000000510'::UUID)
) AS required(menu_id)
WHERE granted.menu_id = '019b0000-0000-7000-8000-00000000024b'
ON CONFLICT (role_id, menu_id) DO NOTHING;
