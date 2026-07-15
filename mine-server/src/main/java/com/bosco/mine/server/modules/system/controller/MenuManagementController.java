package com.bosco.mine.server.modules.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.modules.system.service.AccessManagementService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提供由发布迁移维护的只读菜单和权限节点树。
 */
@RestController
@RequestMapping("/api/v1/system/menus")
public class MenuManagementController {

    private final AccessManagementService service;

    /**
     * 创建只读菜单控制器。
     *
     * @param service 访问管理服务
     */
    public MenuManagementController(AccessManagementService service) {
        this.service = service;
    }

    /**
     * 获取完整菜单和权限树。
     *
     * @return 只读菜单树
     */
    @GetMapping("/tree")
    @SaCheckPermission("system:role:view")
    public List<AccessManagementService.MenuNode> findMenuTree() {
        return service.findMenuTree();
    }
}
