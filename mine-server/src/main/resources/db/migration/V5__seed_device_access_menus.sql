-- 设备管理权限属于代码契约；页面尚未交付前保持菜单隐藏，但权限可以分配和后端校验。
INSERT INTO sys_menu (
    id, parent_id, name, type, path, component_key,
    permission, icon, sort_order, visible
) VALUES
    ('019b0000-0000-7000-8000-000000000200', NULL,
     '设备管理', 'DIRECTORY', '/device', NULL,
     NULL, 'mdi:factory', 100, FALSE),

    ('019b0000-0000-7000-8000-000000000210', '019b0000-0000-7000-8000-000000000200',
     '产品管理', 'MENU', '/device/products', 'device-product-list',
     'device:product:view', 'mdi:package-variant-closed', 10, FALSE),
    ('019b0000-0000-7000-8000-000000000211', '019b0000-0000-7000-8000-000000000210',
     '创建产品', 'BUTTON', NULL, NULL,
     'device:product:create', NULL, 10, FALSE),
    ('019b0000-0000-7000-8000-000000000212', '019b0000-0000-7000-8000-000000000210',
     '修改产品', 'BUTTON', NULL, NULL,
     'device:product:update', NULL, 20, FALSE),
    ('019b0000-0000-7000-8000-000000000213', '019b0000-0000-7000-8000-000000000210',
     '归档产品', 'BUTTON', NULL, NULL,
     'device:product:archive', NULL, 30, FALSE),

    ('019b0000-0000-7000-8000-000000000220', '019b0000-0000-7000-8000-000000000200',
     '设备台账', 'MENU', '/device/devices', 'device-device-list',
     'device:device:view', 'mdi:access-point', 20, FALSE),
    ('019b0000-0000-7000-8000-000000000221', '019b0000-0000-7000-8000-000000000220',
     '创建设备', 'BUTTON', NULL, NULL,
     'device:device:create', NULL, 10, FALSE),
    ('019b0000-0000-7000-8000-000000000222', '019b0000-0000-7000-8000-000000000220',
     '修改设备', 'BUTTON', NULL, NULL,
     'device:device:update', NULL, 20, FALSE),
    ('019b0000-0000-7000-8000-000000000223', '019b0000-0000-7000-8000-000000000220',
     '归档设备', 'BUTTON', NULL, NULL,
     'device:device:archive', NULL, 30, FALSE);
