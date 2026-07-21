-- 在矿区管理下发布三维场景工作台及其最小职责分离权限。
INSERT INTO sys_menu (
    id, parent_id, name, type, path, component_key, permission,
    icon, sort_order, visible, i18n_key, protected_node
) VALUES
    ('019b0000-0000-7000-8000-000000000690', '019b0000-0000-7000-8000-000000000600',
     '三维场景', 'MENU', '/mine/scene', 'mine-scene-workbench', 'mine:scene:view',
     'mdi:terrain', 90, TRUE, 'mine.menu.mineScene', TRUE),
    ('019b0000-0000-7000-8000-000000000691', '019b0000-0000-7000-8000-000000000690',
     '编辑三维场景', 'BUTTON', NULL, NULL, 'mine:scene:edit',
     NULL, 10, FALSE, NULL, TRUE),
    ('019b0000-0000-7000-8000-000000000692', '019b0000-0000-7000-8000-000000000690',
     '上传和归档三维资源', 'BUTTON', NULL, NULL, 'mine:scene:asset:upload',
     NULL, 20, FALSE, NULL, TRUE),
    ('019b0000-0000-7000-8000-000000000693', '019b0000-0000-7000-8000-000000000690',
     '发布和切换默认场景', 'BUTTON', NULL, NULL, 'mine:scene:publish',
     NULL, 30, FALSE, NULL, TRUE);
