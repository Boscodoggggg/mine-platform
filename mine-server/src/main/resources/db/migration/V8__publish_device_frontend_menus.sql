-- 前端页面交付后发布设备域目录与三个页面菜单。
UPDATE sys_menu
SET visible = TRUE
WHERE path IN ('/device', '/device/products', '/device/devices', '/device/monitor');
