package com.bosco.mine.server.modules.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.bootstrap.web.PageResponse;
import com.bosco.mine.server.modules.system.service.AccessManagementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 提供角色维护和角色菜单授权接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1/system/roles")
public class RoleManagementController {

    private static final ErrorCode PAGE_INVALID =
            new ErrorCode("REQUEST-400-002", "分页参数不合法");

    private final AccessManagementService service;

    /**
     * 创建角色管理控制器。
     *
     * @param service 访问管理服务
     */
    public RoleManagementController(AccessManagementService service) {
        this.service = service;
    }

    /**
     * 分页查询角色。
     *
     * @param keyword 角色编码或名称关键词
     * @param status  角色状态
     * @param page    页码
     * @param size    每页数量
     * @return 角色分页数据
     */
    @GetMapping
    @SaCheckPermission("system:role:view")
    public PageResponse<AccessManagementService.RoleView> findRoles(
            @RequestParam(required = false) @Size(max = 128) String keyword,
            @RequestParam(required = false) @Pattern(regexp = "ENABLED|DISABLED") String status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        if (page < 1 || size < 1 || size > 100) {
            throw new MineException(PAGE_INVALID, Map.of("page", page, "size", size));
        }
        return service.findRoles(keyword, status, page, size);
    }

    /**
     * 创建角色。
     *
     * @param request 创建请求
     * @return 新角色及其资源地址
     */
    @PostMapping
    @SaCheckPermission("system:role:create")
    public ResponseEntity<AccessManagementService.RoleView> createRole(
            @Valid @RequestBody RoleCreateRequest request
    ) {
        AccessManagementService.RoleView created = service.createRole(
                request.code(),
                request.name(),
                request.status(),
                request.description()
        );
        URI location = UriComponentsBuilder.fromPath("/api/v1/system/roles/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * 修改角色名称、状态和说明。
     *
     * @param roleId  角色标识
     * @param request 修改请求
     * @return 更新后的角色快照
     */
    @PutMapping("/{id}")
    @SaCheckPermission("system:role:update")
    public AccessManagementService.RoleView updateRole(
            @PathVariable("id") UUID roleId,
            @Valid @RequestBody RoleUpdateRequest request
    ) {
        return service.updateRole(
                roleId,
                request.name(),
                request.status(),
                request.description(),
                request.version()
        );
    }

    /**
     * 删除未分配给用户的角色。
     *
     * @param roleId 角色标识
     * @return 空响应
     */
    @DeleteMapping("/{id}")
    @SaCheckPermission("system:role:delete")
    public ResponseEntity<Void> deleteRole(@PathVariable("id") UUID roleId) {
        service.deleteRole(roleId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 原子替换角色授权节点。
     *
     * @param roleId  角色标识
     * @param request 菜单替换请求
     * @return 空响应
     */
    @PutMapping("/{id}/menus")
    @SaCheckPermission("system:role:assign-menu")
    public ResponseEntity<Void> replaceRoleMenus(
            @PathVariable("id") UUID roleId,
            @Valid @RequestBody RoleMenuReplaceRequest request
    ) {
        service.replaceRoleMenus(roleId, request.menuIds());
        return ResponseEntity.noContent().build();
    }

    /**
     * 角色创建请求。
     *
     * @param code        角色编码
     * @param name        角色名称
     * @param status      角色状态
     * @param description 角色说明
     */
    public record RoleCreateRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]{2,63}") String code,
            @NotBlank @Size(max = 128) String name,
            @NotBlank @Pattern(regexp = "ENABLED|DISABLED") String status,
            @Size(max = 500) String description
    ) {
    }

    /**
     * 角色修改请求，角色编码不允许修改。
     *
     * @param name        角色名称
     * @param status      角色状态
     * @param description 角色说明
     * @param version     期望版本号
     */
    public record RoleUpdateRequest(
            @NotBlank @Size(max = 128) String name,
            @NotBlank @Pattern(regexp = "ENABLED|DISABLED") String status,
            @Size(max = 500) String description,
            @Min(0) long version
    ) {
    }

    /**
     * 角色菜单替换请求。
     *
     * @param menuIds 新授权节点标识集合
     */
    public record RoleMenuReplaceRequest(
            @NotNull @Size(max = 1000) List<@NotNull UUID> menuIds
    ) {
    }
}
