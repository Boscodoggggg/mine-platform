package com.bosco.mine.server.modules.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.bosco.mine.server.modules.system.service.MenuManagementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * 提供菜单、页面和按钮权限节点的在线管理接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1/system/menus")
public class MenuManagementController {

    private final MenuManagementService service;

    /**
     * 创建菜单管理控制器。
     *
     * @param service 菜单管理服务
     */
    public MenuManagementController(MenuManagementService service) {
        this.service = service;
    }

    /**
     * 获取完整菜单和权限树，供角色授权和菜单维护复用。
     *
     * @return 菜单树
     */
    @GetMapping("/tree")
    @SaCheckPermission(value = {"system:role:view", "system:menu:view"}, mode = SaMode.OR)
    public List<MenuManagementService.MenuNode> findMenuTree() {
        return service.findMenuTree();
    }

    /**
     * 查询前端组件白名单。
     *
     * @return 可配置组件列表
     */
    @GetMapping("/component-options")
    @SaCheckPermission("system:menu:view")
    public List<MenuManagementService.ComponentOption> findComponentOptions() {
        return service.findComponentOptions();
    }

    /**
     * 创建菜单节点。
     *
     * @param request 创建请求
     * @return 新节点及资源地址
     */
    @PostMapping
    @SaCheckPermission("system:menu:create")
    public ResponseEntity<MenuManagementService.MenuNode> createMenu(@Valid @RequestBody MenuSaveRequest request) {
        MenuManagementService.MenuNode created = service.createMenu(request.toCommand());
        URI location = UriComponentsBuilder.fromPath("/api/v1/system/menus/{id}")
                .buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * 修改菜单节点。
     *
     * @param id      菜单标识
     * @param request 修改请求
     * @return 更新后的节点
     */
    @PutMapping("/{id}")
    @SaCheckPermission("system:menu:update")
    public MenuManagementService.MenuNode updateMenu(
            @PathVariable UUID id,
            @Valid @RequestBody MenuSaveRequest request
    ) {
        return service.updateMenu(id, request.toCommand(), request.version());
    }

    /**
     * 删除未使用的非核心菜单节点。
     *
     * @param id 菜单标识
     * @return 空响应
     */
    @DeleteMapping("/{id}")
    @SaCheckPermission("system:menu:delete")
    public ResponseEntity<Void> deleteMenu(@PathVariable UUID id) {
        service.deleteMenu(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 菜单保存请求。
     */
    public record MenuSaveRequest(
            UUID parentId,
            @NotBlank @Size(max = 128) String name,
            @NotBlank @Pattern(regexp = "DIRECTORY|MENU|BUTTON") String type,
            @Size(max = 255) String path,
            @Size(max = 128) String componentKey,
            @Size(max = 128) String permission,
            @Size(max = 128) String icon,
            @Min(0) int sortOrder,
            boolean visible,
            @NotBlank @Pattern(regexp = "ENABLED|DISABLED") String status,
            @Size(max = 255) String i18nKey,
            @Min(0) long version
    ) {
        /**
         * 转换为应用层保存命令。
         *
         * @return 保存命令
         */
        MenuManagementService.MenuSaveCommand toCommand() {
            return new MenuManagementService.MenuSaveCommand(
                    parentId, name, type, path, componentKey, permission, icon,
                    sortOrder, visible, status, i18nKey
            );
        }
    }
}
