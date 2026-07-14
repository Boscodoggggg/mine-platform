package com.bosco.mine.server.bootstrap.security;

import cn.dev33.satoken.stp.StpInterface;
import com.bosco.mine.server.modules.system.repository.SystemAuthMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 从五表 RBAC 实时加载 Sa-Token 所需的角色和权限字符串。
 */
@Component
public final class StpInterfaceImpl implements StpInterface {

    private static final List<String> ALL_PERMISSIONS = List.of("*");
    private static final List<String> SUPER_ADMIN_ROLE = List.of("SUPER_ADMIN");

    private final SystemAuthMapper authMapper;
    private final UUID superAdminId;

    /**
     * 创建权限数据适配器。
     *
     * @param authMapper 系统认证数据访问接口
     * @param properties 平台安全配置
     */
    public StpInterfaceImpl(SystemAuthMapper authMapper, MineSecurityProperties properties) {
        this.authMapper = authMapper;
        this.superAdminId = properties.bootstrapAdmin().id();
    }

    /**
     * 获取当前登录用户的权限字符串。
     *
     * @param loginId   登录标识
     * @param loginType 登录类型
     * @return 有效权限列表
     */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        UUID userId = parseUserId(loginId);
        if (userId == null || !authMapper.isEnabledUser(userId)) {
            return List.of();
        }
        if (userId.equals(superAdminId)) {
            return ALL_PERMISSIONS;
        }
        return authMapper.findPermissionCodes(userId);
    }

    /**
     * 获取当前登录用户的角色编码。
     *
     * @param loginId   登录标识
     * @param loginType 登录类型
     * @return 有效角色列表
     */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        UUID userId = parseUserId(loginId);
        if (userId == null || !authMapper.isEnabledUser(userId)) {
            return List.of();
        }
        if (userId.equals(superAdminId)) {
            return SUPER_ADMIN_ROLE;
        }
        return authMapper.findRoleCodes(userId);
    }

    private UUID parseUserId(Object loginId) {
        try {
            return loginId == null ? null : UUID.fromString(loginId.toString());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
