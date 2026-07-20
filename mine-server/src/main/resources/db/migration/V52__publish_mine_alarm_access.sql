-- 为三维场景发布强矿区作用域的车载设备告警查看权限。
INSERT INTO sys_menu (
    id, parent_id, name, type, path, component_key, permission,
    icon, sort_order, visible, i18n_key, protected_node
) VALUES
    ('019b0000-0000-7000-8000-000000000694', '019b0000-0000-7000-8000-000000000690',
     '查看矿区车载告警', 'BUTTON', NULL, NULL, 'mine:alarm:view',
     NULL, 40, FALSE, NULL, TRUE);
