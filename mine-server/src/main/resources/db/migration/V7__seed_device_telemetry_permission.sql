-- 设备监控页面尚未交付前保持隐藏；菜单节点同时承载后端遥测查询权限。
INSERT INTO sys_menu (
    id, parent_id, name, type, path, component_key,
    permission, icon, sort_order, visible
) VALUES (
    '019b0000-0000-7000-8000-000000000230',
    '019b0000-0000-7000-8000-000000000200',
    '设备监控', 'MENU', '/device/monitor', 'device-monitor',
    'device:telemetry:view', 'mdi:monitor-dashboard', 30, FALSE
);
