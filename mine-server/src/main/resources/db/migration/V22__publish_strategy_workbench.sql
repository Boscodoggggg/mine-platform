-- 将既有策略查看权限节点原位升级为独立工作台，保留已有角色授权关系。
UPDATE sys_menu
SET parent_id = '019b0000-0000-7000-8000-000000000200',
    name = '策略工作台',
    type = 'MENU',
    path = '/device/strategies',
    component_key = 'device-strategy-workbench',
    icon = 'mdi:tune-variant',
    sort_order = 35,
    visible = TRUE,
    i18n_key = 'mine.menu.strategies',
    protected_node = TRUE,
    updated_at = CURRENT_TIMESTAMP,
    version = version + 1
WHERE id = '019b0000-0000-7000-8000-00000000024b';

-- 设备组维护和策略发布能力归属独立工作台，原权限标识保持不变。
UPDATE sys_menu
SET parent_id = '019b0000-0000-7000-8000-00000000024b',
    updated_at = CURRENT_TIMESTAMP,
    version = version + 1
WHERE id IN (
    '019b0000-0000-7000-8000-000000000249',
    '019b0000-0000-7000-8000-00000000024a',
    '019b0000-0000-7000-8000-00000000024c',
    '019b0000-0000-7000-8000-00000000024d'
);
