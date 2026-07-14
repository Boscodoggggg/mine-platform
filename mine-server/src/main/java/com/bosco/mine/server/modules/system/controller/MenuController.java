package com.bosco.mine.server.modules.system.controller;

import com.bosco.mine.server.modules.system.service.NavigationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供当前用户动态菜单和按钮权限契约。
 */
@RestController
@RequestMapping("/api/v1/auth")
public final class MenuController {

    private final NavigationService navigationService;

    /**
     * 创建当前用户菜单控制器。
     *
     * @param navigationService 导航服务
     */
    public MenuController(NavigationService navigationService) {
        this.navigationService = navigationService;
    }

    /**
     * 返回当前用户的角色、权限和目录/菜单/按钮树。
     *
     * @return 当前用户导航结果
     */
    @GetMapping("/menus")
    public NavigationService.NavigationResult menus() {
        return navigationService.currentNavigation();
    }
}
