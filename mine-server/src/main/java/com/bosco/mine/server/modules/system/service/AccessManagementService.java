package com.bosco.mine.server.modules.system.service;

import cn.dev33.satoken.stp.StpUtil;
import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.server.bootstrap.security.MineSecurityProperties;
import com.bosco.mine.server.bootstrap.web.PageResponse;
import com.bosco.mine.server.modules.system.repository.AccessManagementMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 处理系统用户、角色及其授权关系的管理用例。
 */
@Service
public class AccessManagementService {

    private static final ErrorCode USER_NOT_FOUND =
            new ErrorCode("SYSTEM-404-001", "用户不存在");
    private static final ErrorCode ROLE_NOT_FOUND =
            new ErrorCode("SYSTEM-404-002", "角色不存在或未启用");
    private static final ErrorCode USERNAME_CONFLICT =
            new ErrorCode("SYSTEM-409-001", "用户名已存在");
    private static final ErrorCode VERSION_CONFLICT =
            new ErrorCode("SYSTEM-409-003", "数据已被其他管理员修改，请刷新后重试");
    private static final ErrorCode ROLE_IN_USE =
            new ErrorCode("SYSTEM-409-004", "角色仍被用户使用，请先解除关联");
    private static final ErrorCode SUPER_ADMIN_PROTECTED =
            new ErrorCode("SYSTEM-403-001", "禁止修改超级管理员");
    private static final ErrorCode PASSWORD_INVALID =
            new ErrorCode("SYSTEM-422-001", "临时密码不符合安全要求");
    private static final ErrorCode ROLE_CODE_CONFLICT =
            new ErrorCode("SYSTEM-409-002", "角色编码已存在");
    private static final ErrorCode MENU_NOT_FOUND =
            new ErrorCode("SYSTEM-404-003", "菜单或权限节点不存在或未启用");
    /** 数据保留治理入口标识。 */
    private static final UUID RETENTION_GOVERNANCE_MENU_ID =
            UUID.fromString("019b0000-0000-7000-8000-000000000510");
    /** 数据保留页面正常读取产品、设备和策略上下文所需的授权节点。 */
    private static final List<UUID> RETENTION_GOVERNANCE_DEPENDENCIES = List.of(
            UUID.fromString("019b0000-0000-7000-8000-000000000210"),
            UUID.fromString("019b0000-0000-7000-8000-000000000220"),
            UUID.fromString("019b0000-0000-7000-8000-00000000024b")
    );
    /** 三维场景工作台入口标识。 */
    private static final UUID SCENE_WORKBENCH_MENU_ID =
            UUID.fromString("019b0000-0000-7000-8000-000000000690");
    /** 三维场景编辑器读取矿区统一坐标模型所需的最小只读节点。 */
    private static final UUID COORDINATE_VIEW_MENU_ID =
            UUID.fromString("019b0000-0000-7000-8000-000000000642");

    private final AccessManagementMapper mapper;
    private final PasswordEncoder passwordEncoder;
    private final OperationAuditService auditService;
    private final UUID superAdminId;
    private final IdGenerator idGenerator = IdGenerator.timeOrdered();

    /**
     * 创建访问管理服务。
     *
     * @param mapper          访问管理数据接口
     * @param passwordEncoder 密码编码器
     * @param properties      平台安全配置
     * @param auditService    操作审计服务
     */
    public AccessManagementService(
            AccessManagementMapper mapper,
            PasswordEncoder passwordEncoder,
            MineSecurityProperties properties,
            OperationAuditService auditService
    ) {
        this.mapper = mapper;
        this.passwordEncoder = passwordEncoder;
        this.superAdminId = properties.bootstrapAdmin().id();
        this.auditService = auditService;
    }

    /**
     * 分页查询用户及其角色标识。
     *
     * @param keyword 用户名或展示名称关键词
     * @param status  用户状态
     * @param page    页码，从一开始
     * @param size    每页数量
     * @return 用户分页数据
     */
    @Transactional(readOnly = true)
    public PageResponse<UserView> findUsers(String keyword, String status, int page, int size) {
        String normalizedKeyword = normalizeOptional(keyword);
        String normalizedStatus = normalizeOptional(status);
        int offset = Math.multiplyExact(page - 1, size);
        List<UserView> users = mapper.findUsers(normalizedKeyword, normalizedStatus, offset, size)
                .stream()
                .map(this::toUserView)
                .toList();
        return new PageResponse<>(
                users,
                page,
                size,
                mapper.countUsers(normalizedKeyword, normalizedStatus)
        );
    }

    /**
     * 创建普通用户并保存临时密码散列。
     *
     * @param username          登录名
     * @param displayName       展示名称
     * @param temporaryPassword 临时密码
     * @param status            用户状态
     * @return 新用户快照
     */
    @Transactional
    public UserView createUser(
            String username,
            String displayName,
            String temporaryPassword,
            String status
    ) {
        String normalizedUsername = username.strip();
        validateTemporaryPassword(temporaryPassword);
        if (mapper.usernameExists(normalizedUsername)) {
            throw new MineException(USERNAME_CONFLICT, Map.of("username", normalizedUsername));
        }

        UUID userId = idGenerator.next();
        try {
            mapper.insertUser(
                    userId,
                    normalizedUsername,
                    passwordEncoder.encode(temporaryPassword),
                    displayName.strip(),
                    normalizeStatus(status),
                    currentOperatorId()
            );
        } catch (DuplicateKeyException exception) {
            throw new MineException(USERNAME_CONFLICT, Map.of("username", normalizedUsername));
        }
        UserView created = userById(userId);
        auditService.recordSuccess(
                "CREATE_USER",
                "system:user:create",
                "USER",
                userId,
                Map.of(),
                userSummary(created)
        );
        return created;
    }

    /**
     * 通过乐观锁修改用户展示名称和状态。
     *
     * @param userId      用户标识
     * @param displayName 展示名称
     * @param status      用户状态
     * @param version     期望版本号
     * @return 更新后的用户快照
     */
    @Transactional
    public UserView updateUser(
            UUID userId,
            String displayName,
            String status,
            long version
    ) {
        protectSuperAdmin(userId);
        UserView before = userById(userId);
        int affected = mapper.updateUser(
                userId,
                displayName.strip(),
                normalizeStatus(status),
                version,
                currentOperatorId()
        );
        if (affected == 0) {
            throw new MineException(VERSION_CONFLICT, Map.of("userId", userId));
        }
        if (!"ENABLED".equals(normalizeStatus(status))) {
            StpUtil.logout(userId.toString());
        }
        UserView updated = userById(userId);
        auditService.recordSuccess(
                "UPDATE_USER",
                "system:user:update",
                "USER",
                userId,
                userSummary(before),
                userSummary(updated)
        );
        return updated;
    }

    /**
     * 原子替换用户关联的全部角色。
     *
     * @param userId  用户标识
     * @param roleIds 新角色标识集合
     */
    @Transactional
    public void replaceUserRoles(UUID userId, List<UUID> roleIds) {
        protectSuperAdmin(userId);
        requireUser(userId);
        List<UUID> previousRoleIds = mapper.findRoleIdsByUserId(userId);
        List<UUID> uniqueRoleIds = List.copyOf(new LinkedHashSet<>(roleIds));
        if (!uniqueRoleIds.isEmpty()
                && mapper.countEnabledRoleIds(uniqueRoleIds) != uniqueRoleIds.size()) {
            throw new MineException(ROLE_NOT_FOUND, Map.of());
        }

        mapper.deleteUserRoles(userId);
        UUID operatorId = currentOperatorId();
        uniqueRoleIds.forEach(roleId -> mapper.insertUserRole(
                idGenerator.next(),
                userId,
                roleId,
                operatorId
        ));
        auditService.recordSuccess(
                "ASSIGN_USER_ROLES",
                "system:user:assign-role",
                "USER",
                userId,
                Map.of("roleIds", previousRoleIds),
                Map.of("roleIds", uniqueRoleIds)
        );
    }

    /**
     * 设置用户临时密码并注销该用户的全部旧会话。
     *
     * @param userId            用户标识
     * @param temporaryPassword 新临时密码
     */
    @Transactional
    public void resetUserPassword(UUID userId, String temporaryPassword) {
        protectSuperAdmin(userId);
        requireUser(userId);
        validateTemporaryPassword(temporaryPassword);
        mapper.resetUserPassword(
                userId,
                passwordEncoder.encode(temporaryPassword),
                currentOperatorId()
        );
        auditService.recordSuccess(
                "RESET_USER_PASSWORD",
                "system:user:reset-password",
                "USER",
                userId,
                Map.of(),
                Map.of("passwordChangeRequired", true)
        );
        StpUtil.logout(userId.toString());
    }

    /**
     * 分页查询角色及其菜单授权标识。
     *
     * @param keyword 角色编码或名称关键词
     * @param status  角色状态
     * @param page    页码，从一开始
     * @param size    每页数量
     * @return 角色分页数据
     */
    @Transactional(readOnly = true)
    public PageResponse<RoleView> findRoles(String keyword, String status, int page, int size) {
        String normalizedKeyword = normalizeOptional(keyword);
        String normalizedStatus = normalizeOptional(status);
        int offset = Math.multiplyExact(page - 1, size);
        List<RoleView> roles = mapper.findRoles(normalizedKeyword, normalizedStatus, offset, size)
                .stream()
                .map(this::toRoleView)
                .toList();
        return new PageResponse<>(
                roles,
                page,
                size,
                mapper.countRoles(normalizedKeyword, normalizedStatus)
        );
    }

    /**
     * 创建角色，角色编码创建后不可修改。
     *
     * @param code        角色编码
     * @param name        角色名称
     * @param status      角色状态
     * @param description 角色说明
     * @return 新角色快照
     */
    @Transactional
    public RoleView createRole(String code, String name, String status, String description) {
        String normalizedCode = code.strip().toUpperCase(Locale.ROOT);
        if (mapper.roleCodeExists(normalizedCode)) {
            throw new MineException(ROLE_CODE_CONFLICT, Map.of("code", normalizedCode));
        }
        UUID roleId = idGenerator.next();
        try {
            mapper.insertRole(
                    roleId,
                    normalizedCode,
                    name.strip(),
                    normalizeStatus(status),
                    normalizeOptional(description),
                    currentOperatorId()
            );
        } catch (DuplicateKeyException exception) {
            throw new MineException(ROLE_CODE_CONFLICT, Map.of("code", normalizedCode));
        }
        RoleView created = roleById(roleId);
        auditService.recordSuccess(
                "CREATE_ROLE",
                "system:role:create",
                "ROLE",
                roleId,
                Map.of(),
                roleSummary(created)
        );
        return created;
    }

    /**
     * 通过乐观锁修改角色可变信息。
     *
     * @param roleId      角色标识
     * @param name        角色名称
     * @param status      角色状态
     * @param description 角色说明
     * @param version     期望版本号
     * @return 更新后的角色快照
     */
    @Transactional
    public RoleView updateRole(
            UUID roleId,
            String name,
            String status,
            String description,
            long version
    ) {
        RoleView before = roleById(roleId);
        int affected = mapper.updateRole(
                roleId,
                name.strip(),
                normalizeStatus(status),
                normalizeOptional(description),
                version,
                currentOperatorId()
        );
        if (affected == 0) {
            throw new MineException(VERSION_CONFLICT, Map.of("roleId", roleId));
        }
        RoleView updated = roleById(roleId);
        auditService.recordSuccess(
                "UPDATE_ROLE",
                "system:role:update",
                "ROLE",
                roleId,
                roleSummary(before),
                roleSummary(updated)
        );
        return updated;
    }

    /**
     * 删除未分配给任何用户的角色。
     *
     * @param roleId 角色标识
     */
    @Transactional
    public void deleteRole(UUID roleId) {
        RoleView before = roleById(roleId);
        if (mapper.deleteUnusedRole(roleId) == 0) {
            throw new MineException(ROLE_IN_USE, Map.of("roleId", roleId));
        }
        auditService.recordSuccess(
                "DELETE_ROLE",
                "system:role:delete",
                "ROLE",
                roleId,
                roleSummary(before),
                Map.of()
        );
    }

    /**
     * 原子替换角色拥有的全部目录、页面和按钮节点。
     *
     * @param roleId  角色标识
     * @param menuIds 新授权节点标识集合
     */
    @Transactional
    public void replaceRoleMenus(UUID roleId, List<UUID> menuIds) {
        requireRole(roleId);
        List<UUID> previousMenuIds = mapper.findMenuIdsByRoleId(roleId);
        List<UUID> uniqueMenuIds = expandMenuDependencies(menuIds);
        if (!uniqueMenuIds.isEmpty()
                && mapper.countEnabledMenuIds(uniqueMenuIds) != uniqueMenuIds.size()) {
            throw new MineException(MENU_NOT_FOUND, Map.of());
        }

        mapper.deleteRoleMenus(roleId);
        UUID operatorId = currentOperatorId();
        uniqueMenuIds.forEach(menuId -> mapper.insertRoleMenu(
                idGenerator.next(),
                roleId,
                menuId,
                operatorId
        ));
        auditService.recordSuccess(
                "ASSIGN_ROLE_MENUS",
                "system:role:assign-menu",
                "ROLE",
                roleId,
                Map.of("menuIds", previousMenuIds),
                Map.of("menuIds", uniqueMenuIds)
        );
    }

    /**
     * 展开治理入口所需的最小读取授权，避免角色获得可见但不可用的菜单。
     *
     * @param menuIds 管理员选择的授权节点
     * @return 去重并补齐依赖后的授权节点
     */
    private List<UUID> expandMenuDependencies(List<UUID> menuIds) {
        LinkedHashSet<UUID> expanded = new LinkedHashSet<>(menuIds);
        if (expanded.contains(RETENTION_GOVERNANCE_MENU_ID)) {
            expanded.addAll(RETENTION_GOVERNANCE_DEPENDENCIES);
        }
        if (expanded.contains(SCENE_WORKBENCH_MENU_ID)) {
            expanded.add(COORDINATE_VIEW_MENU_ID);
        }
        return List.copyOf(expanded);
    }

    /**
     * 获取由发布迁移维护的完整只读菜单树。
     *
     * @return 菜单树根节点
     */
    @Transactional(readOnly = true)
    public List<MenuNode> findMenuTree() {
        Map<UUID, List<AccessManagementMapper.ManagedMenuRecord>> children = new HashMap<>();
        for (AccessManagementMapper.ManagedMenuRecord menu : mapper.findAllMenus()) {
            children.computeIfAbsent(menu.parentId(), ignored -> new java.util.ArrayList<>()).add(menu);
        }
        return children.getOrDefault(null, List.of())
                .stream()
                .map(menu -> toMenuNode(menu, children))
                .toList();
    }

    private UserView userById(UUID userId) {
        return toUserView(requireUser(userId));
    }

    private AccessManagementMapper.ManagedUserRecord requireUser(UUID userId) {
        return mapper.findManagedUserById(userId)
                .orElseThrow(() -> new MineException(USER_NOT_FOUND, Map.of("userId", userId)));
    }

    private RoleView roleById(UUID roleId) {
        return toRoleView(requireRole(roleId));
    }

    private AccessManagementMapper.ManagedRoleRecord requireRole(UUID roleId) {
        return mapper.findManagedRoleById(roleId)
                .orElseThrow(() -> new MineException(ROLE_NOT_FOUND, Map.of("roleId", roleId)));
    }

    private UserView toUserView(AccessManagementMapper.ManagedUserRecord user) {
        return new UserView(
                user.id(),
                user.username(),
                user.displayName(),
                user.status(),
                user.passwordChangeRequired(),
                user.lastLoginAt(),
                user.createdAt(),
                user.updatedAt(),
                user.version(),
                mapper.findRoleIdsByUserId(user.id())
        );
    }

    private RoleView toRoleView(AccessManagementMapper.ManagedRoleRecord role) {
        return new RoleView(
                role.id(),
                role.code(),
                role.name(),
                role.status(),
                role.description(),
                role.createdAt(),
                role.updatedAt(),
                role.version(),
                mapper.findMenuIdsByRoleId(role.id())
        );
    }

    private MenuNode toMenuNode(
            AccessManagementMapper.ManagedMenuRecord menu,
            Map<UUID, List<AccessManagementMapper.ManagedMenuRecord>> children
    ) {
        return new MenuNode(
                menu.id(),
                menu.parentId(),
                menu.name(),
                menu.type(),
                menu.path(),
                menu.componentKey(),
                menu.permission(),
                menu.icon(),
                menu.sortOrder(),
                menu.visible(),
                menu.status(),
                children.getOrDefault(menu.id(), List.of())
                        .stream()
                        .map(child -> toMenuNode(child, children))
                        .toList()
        );
    }

    private void validateTemporaryPassword(String password) {
        int byteLength = password == null
                ? 0
                : password.getBytes(StandardCharsets.UTF_8).length;
        if (byteLength < 12 || byteLength > 72) {
            throw new MineException(PASSWORD_INVALID, Map.of());
        }
    }

    private void protectSuperAdmin(UUID userId) {
        if (superAdminId.equals(userId)) {
            throw new MineException(SUPER_ADMIN_PROTECTED, Map.of());
        }
    }

    private String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private String normalizeStatus(String status) {
        return status.strip().toUpperCase(Locale.ROOT);
    }

    private UUID currentOperatorId() {
        try {
            return UUID.fromString(StpUtil.getLoginIdAsString());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private Map<String, ?> userSummary(UserView user) {
        return Map.of(
                "username", user.username(),
                "displayName", user.displayName(),
                "status", user.status(),
                "roleIds", user.roleIds(),
                "passwordChangeRequired", user.passwordChangeRequired()
        );
    }

    private Map<String, ?> roleSummary(RoleView role) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("code", role.code());
        summary.put("name", role.name());
        summary.put("status", role.status());
        summary.put("menuIds", role.menuIds());
        if (role.description() != null) {
            summary.put("description", role.description());
        }
        return Map.copyOf(summary);
    }

    /**
     * 管理端可见的用户快照，不包含密码及其散列。
     *
     * @param id                     用户标识
     * @param username               登录名
     * @param displayName            展示名称
     * @param status                 用户状态
     * @param passwordChangeRequired 是否必须修改密码
     * @param lastLoginAt            最近登录时间
     * @param createdAt              创建时间
     * @param updatedAt              更新时间
     * @param version                乐观锁版本号
     * @param roleIds                已分配角色标识
     */
    public record UserView(
            UUID id,
            String username,
            String displayName,
            String status,
            boolean passwordChangeRequired,
            Instant lastLoginAt,
            Instant createdAt,
            Instant updatedAt,
            long version,
            List<UUID> roleIds
    ) {

        /**
         * 创建不可变用户快照。
         */
        public UserView {
            roleIds = List.copyOf(roleIds);
        }
    }

    /**
     * 管理端可见的角色快照。
     *
     * @param id          角色标识
     * @param code        不可变角色编码
     * @param name        角色名称
     * @param status      角色状态
     * @param description 角色说明
     * @param createdAt   创建时间
     * @param updatedAt   更新时间
     * @param version     乐观锁版本号
     * @param menuIds     已授权菜单节点标识
     */
    public record RoleView(
            UUID id,
            String code,
            String name,
            String status,
            String description,
            Instant createdAt,
            Instant updatedAt,
            long version,
            List<UUID> menuIds
    ) {

        /**
         * 创建不可变角色快照。
         */
        public RoleView {
            menuIds = List.copyOf(menuIds);
        }
    }

    /**
     * 管理端只读权限树节点。
     *
     * @param id           节点标识
     * @param parentId     父节点标识
     * @param name         展示名称
     * @param type         节点类型
     * @param path         路由路径
     * @param componentKey 前端组件键
     * @param permission   权限码
     * @param icon         图标键
     * @param sortOrder    排序值
     * @param visible      是否在导航显示
     * @param status       节点状态
     * @param children     子节点
     */
    public record MenuNode(
            UUID id,
            UUID parentId,
            String name,
            String type,
            String path,
            String componentKey,
            String permission,
            String icon,
            int sortOrder,
            boolean visible,
            String status,
            List<MenuNode> children
    ) {

        /**
         * 创建不可变权限树节点。
         */
        public MenuNode {
            children = List.copyOf(children);
        }
    }
}
