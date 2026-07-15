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
    private static final ErrorCode SUPER_ADMIN_PROTECTED =
            new ErrorCode("SYSTEM-403-001", "禁止修改超级管理员");
    private static final ErrorCode PASSWORD_INVALID =
            new ErrorCode("SYSTEM-422-001", "临时密码不符合安全要求");

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

    private UserView userById(UUID userId) {
        return toUserView(requireUser(userId));
    }

    private AccessManagementMapper.ManagedUserRecord requireUser(UUID userId) {
        return mapper.findManagedUserById(userId)
                .orElseThrow(() -> new MineException(USER_NOT_FOUND, Map.of("userId", userId)));
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
}
