-- 在矿区管理下发布路线与围栏工作台，并注册事件查询和重放所需的精确权限。
INSERT INTO sys_menu (
    id, parent_id, name, type, path, component_key, permission,
    icon, sort_order, visible, i18n_key, protected_node
) VALUES
    ('019b0000-0000-7000-8000-000000000670', '019b0000-0000-7000-8000-000000000600',
     '路线与围栏', 'MENU', '/mine/spatial', 'mine-spatial-management', 'mine:spatial:view',
     'mdi:vector-polyline', 70, TRUE, 'mine.menu.mineSpatial', TRUE),
    ('019b0000-0000-7000-8000-000000000671', '019b0000-0000-7000-8000-000000000670',
     '编辑路线与围栏', 'BUTTON', NULL, NULL, 'mine:spatial:edit',
     NULL, 10, FALSE, NULL, TRUE),
    ('019b0000-0000-7000-8000-000000000672', '019b0000-0000-7000-8000-000000000670',
     '发布空间版本', 'BUTTON', NULL, NULL, 'mine:spatial:publish',
     NULL, 20, FALSE, NULL, TRUE),
    ('019b0000-0000-7000-8000-000000000673', '019b0000-0000-7000-8000-000000000670',
     '查看空间事件', 'BUTTON', NULL, NULL, 'mine:spatial:event:view',
     NULL, 30, FALSE, NULL, TRUE),
    ('019b0000-0000-7000-8000-000000000674', '019b0000-0000-7000-8000-000000000670',
     '重放空间事件', 'BUTTON', NULL, NULL, 'mine:spatial:replay',
     NULL, 40, FALSE, NULL, TRUE);
