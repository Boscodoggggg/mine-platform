-- 发布系统运行监控菜单与只读权限；不预置普通角色授权。
INSERT INTO sys_menu (
    id, parent_id, name, type, path, component_key,
    permission, icon, sort_order, visible
) VALUES
    ('019b0000-0000-7000-8000-000000000130', '019b0000-0000-7000-8000-000000000100',
     '运行监控', 'MENU', '/system/operations', 'system-operations-monitor',
     'system:operations:view', 'mdi:server-network-outline', 30, TRUE);
