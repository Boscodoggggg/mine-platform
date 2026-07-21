-- 发布命令中心菜单与按钮权限；不预置普通角色授权。
INSERT INTO sys_menu (
    id, parent_id, name, type, path, component_key,
    permission, icon, sort_order, visible
) VALUES
    ('019b0000-0000-7000-8000-000000000300', NULL,
     '命令管理', 'DIRECTORY', '/command', NULL,
     NULL, 'mdi:console-network-outline', 200, TRUE),
    ('019b0000-0000-7000-8000-000000000310', '019b0000-0000-7000-8000-000000000300',
     '命令中心', 'MENU', '/command/center', 'command-center',
     'command:instance:view', 'mdi:send-clock-outline', 10, TRUE),
    ('019b0000-0000-7000-8000-000000000311', '019b0000-0000-7000-8000-000000000310',
     '查看命令模板', 'BUTTON', NULL, NULL,
     'command:template:view', NULL, 10, FALSE),
    ('019b0000-0000-7000-8000-000000000312', '019b0000-0000-7000-8000-000000000310',
     '下发低风险命令', 'BUTTON', NULL, NULL,
     'command:instance:create', NULL, 20, FALSE);
