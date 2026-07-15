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
 * 提供系统用户维护、角色分配和临时密码重置接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1/system/users")
public class UserManagementController {

    private static final ErrorCode PAGE_INVALID =
            new ErrorCode("REQUEST-400-002", "分页参数不合法");

    private final AccessManagementService service;

    /**
     * 创建用户管理控制器。
     *
     * @param service 访问管理服务
     */
    public UserManagementController(AccessManagementService service) {
        this.service = service;
    }

    /**
     * 分页查询系统用户。
     *
     * @param keyword 用户名或展示名称关键词
     * @param status  用户状态
     * @param page    页码
     * @param size    每页数量
     * @return 用户分页数据
     */
    @GetMapping
    @SaCheckPermission("system:user:view")
    public PageResponse<AccessManagementService.UserView> findUsers(
            @RequestParam(required = false) @Size(max = 128) String keyword,
            @RequestParam(required = false)
            @Pattern(regexp = "ENABLED|DISABLED|LOCKED") String status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        if (page < 1 || size < 1 || size > 100) {
            throw new MineException(PAGE_INVALID, Map.of("page", page, "size", size));
        }
        return service.findUsers(keyword, status, page, size);
    }

    /**
     * 创建普通系统用户。
     *
     * @param request 创建请求
     * @return 创建后的用户及资源地址
     */
    @PostMapping
    @SaCheckPermission("system:user:create")
    public ResponseEntity<AccessManagementService.UserView> createUser(
            @Valid @RequestBody UserCreateRequest request
    ) {
        AccessManagementService.UserView created = service.createUser(
                request.username(),
                request.displayName(),
                request.temporaryPassword(),
                request.status()
        );
        URI location = UriComponentsBuilder.fromPath("/api/v1/system/users/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * 修改普通用户基本资料和状态。
     *
     * @param userId  用户标识
     * @param request 修改请求
     * @return 更新后的用户快照
     */
    @PutMapping("/{id}")
    @SaCheckPermission("system:user:update")
    public AccessManagementService.UserView updateUser(
            @PathVariable("id") UUID userId,
            @Valid @RequestBody UserUpdateRequest request
    ) {
        return service.updateUser(
                userId,
                request.displayName(),
                request.status(),
                request.version()
        );
    }

    /**
     * 原子替换用户角色。
     *
     * @param userId  用户标识
     * @param request 角色替换请求
     * @return 空响应
     */
    @PutMapping("/{id}/roles")
    @SaCheckPermission("system:user:assign-role")
    public ResponseEntity<Void> replaceUserRoles(
            @PathVariable("id") UUID userId,
            @Valid @RequestBody UserRoleReplaceRequest request
    ) {
        service.replaceUserRoles(userId, request.roleIds());
        return ResponseEntity.noContent().build();
    }

    /**
     * 设置临时密码并注销用户旧会话。
     *
     * @param userId  用户标识
     * @param request 密码重置请求
     * @return 空响应
     */
    @PostMapping("/{id}/password-reset")
    @SaCheckPermission("system:user:reset-password")
    public ResponseEntity<Void> resetUserPassword(
            @PathVariable("id") UUID userId,
            @Valid @RequestBody PasswordResetRequest request
    ) {
        service.resetUserPassword(userId, request.temporaryPassword());
        return ResponseEntity.noContent().build();
    }

    /**
     * 用户创建请求。
     *
     * @param username          登录名
     * @param displayName       展示名称
     * @param temporaryPassword 临时密码
     * @param status            初始状态
     */
    public record UserCreateRequest(
            @NotBlank
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{2,63}") String username,
            @NotBlank @Size(max = 128) String displayName,
            @NotBlank @Size(min = 12, max = 72) String temporaryPassword,
            @NotBlank @Pattern(regexp = "ENABLED|DISABLED") String status
    ) {
    }

    /**
     * 用户修改请求。
     *
     * @param displayName 展示名称
     * @param status      用户状态
     * @param version     期望版本号
     */
    public record UserUpdateRequest(
            @NotBlank @Size(max = 128) String displayName,
            @NotBlank @Pattern(regexp = "ENABLED|DISABLED") String status,
            @Min(0) long version
    ) {
    }

    /**
     * 用户角色替换请求。
     *
     * @param roleIds 新角色标识集合
     */
    public record UserRoleReplaceRequest(
            @NotNull @Size(max = 100) List<@NotNull UUID> roleIds
    ) {
    }

    /**
     * 临时密码重置请求。
     *
     * @param temporaryPassword 新临时密码
     */
    public record PasswordResetRequest(
            @NotBlank @Size(min = 12, max = 72) String temporaryPassword
    ) {
    }
}
