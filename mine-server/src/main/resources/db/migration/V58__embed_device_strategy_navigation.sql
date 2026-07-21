-- 将设备运行策略收回产品与设备上下文，取消独立策略工作台菜单。
UPDATE sys_menu
SET parent_id = '019b0000-0000-7000-8000-000000000210',
    name = CASE id
        WHEN '019b0000-0000-7000-8000-00000000024b'::UUID THEN '查看运行策略'
        ELSE name
    END,
    type = 'BUTTON',
    path = NULL,
    component_key = NULL,
    icon = NULL,
    visible = FALSE,
    i18n_key = NULL,
    protected_node = TRUE,
    updated_at = CURRENT_TIMESTAMP,
    version = version + 1
WHERE id IN (
    '019b0000-0000-7000-8000-000000000249',
    '019b0000-0000-7000-8000-00000000024a',
    '019b0000-0000-7000-8000-00000000024b',
    '019b0000-0000-7000-8000-00000000024c',
    '019b0000-0000-7000-8000-00000000024d'
);
