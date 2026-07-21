package com.bosco.mine.server.bootstrap.websocket;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 实时连接使用的用户和权限快照。
 *
 * @param userId 用户标识
 * @param permissions 当前权限集合
 */
public record RealtimePrincipal(UUID userId, Set<String> permissions) {

    /**
     * 创建不可变用户权限快照。
     */
    public RealtimePrincipal {
        userId = Objects.requireNonNull(userId, "用户标识不能为空");
        permissions = Set.copyOf(Objects.requireNonNull(permissions, "权限集合不能为空"));
    }

    /**
     * 判断用户是否具备指定权限。
     *
     * @param permission 权限编码
     * @return 是否具备权限
     */
    public boolean hasPermission(String permission) {
        return permissions.contains(permission) || permissions.contains("*");
    }
}
