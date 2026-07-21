-- 凭据管理属于设备台账的敏感按钮权限，不单独生成导航菜单。
INSERT INTO sys_menu (
    id, parent_id, name, type, permission, sort_order, visible
) VALUES
    ('019b0000-0000-7000-8000-000000000224', '019b0000-0000-7000-8000-000000000220',
     '签发设备凭据', 'BUTTON', 'device:credential:create', 40, FALSE),
    ('019b0000-0000-7000-8000-000000000225', '019b0000-0000-7000-8000-000000000220',
     '轮换设备凭据', 'BUTTON', 'device:credential:rotate', 50, FALSE),
    ('019b0000-0000-7000-8000-000000000226', '019b0000-0000-7000-8000-000000000220',
     '吊销设备凭据', 'BUTTON', 'device:credential:revoke', 60, FALSE);
