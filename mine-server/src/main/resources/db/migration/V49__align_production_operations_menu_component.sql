-- 将已发布生产运营菜单切换到前端固定组件白名单键，确保升级环境立即显示页面。

UPDATE sys_menu
SET component_key = 'mine-production-operations',
    updated_at = CURRENT_TIMESTAMP,
    version = version + 1
WHERE id = '019b0000-0000-7000-8000-000000000680'
  AND component_key = 'mine-operation-management';
