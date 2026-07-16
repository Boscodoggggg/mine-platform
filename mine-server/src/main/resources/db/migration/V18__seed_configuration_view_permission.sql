-- 配置工作台需要独立查看发布历史和运行节点加载结果。
INSERT INTO sys_menu (
    id, parent_id, name, type, permission, sort_order, visible, protected_node
) VALUES (
    '019b0000-0000-7000-8000-000000000248',
    '019b0000-0000-7000-8000-000000000240',
    '查看配置发布',
    'BUTTON',
    'device:configuration:view',
    55,
    FALSE,
    TRUE
);
