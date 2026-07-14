package com.bosco.mine.server.modules.system.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 访问系统用户及五表 RBAC 所需的最小认证数据。
 */
@Mapper
public interface SystemAuthMapper {

    /**
     * 按登录名查询用户。
     *
     * @param username 登录名
     * @return 用户认证记录
     */
    @Select("""
            SELECT id, username, password_hash, display_name, status, password_change_required
            FROM sys_user
            WHERE username = #{username}
            """)
    Optional<AuthUserRecord> findUserByUsername(@Param("username") String username);

    /**
     * 按标识查询用户。
     *
     * @param userId 用户标识
     * @return 用户认证记录
     */
    @Select("""
            SELECT id, username, password_hash, display_name, status, password_change_required
            FROM sys_user
            WHERE id = #{userId}
            """)
    Optional<AuthUserRecord> findUserById(@Param("userId") UUID userId);

    /**
     * 判断用户是否存在且处于启用状态。
     *
     * @param userId 用户标识
     * @return 是否为有效用户
     */
    @Select("""
            SELECT EXISTS (
                SELECT 1 FROM sys_user WHERE id = #{userId} AND status = 'ENABLED'
            )
            """)
    boolean isEnabledUser(@Param("userId") UUID userId);

    /**
     * 统计系统用户数量。
     *
     * @return 用户总数
     */
    @Select("SELECT COUNT(*) FROM sys_user")
    long countUsers();

    /**
     * 创建首次启动的超级管理员。
     *
     * @param id           用户标识
     * @param username     登录名
     * @param passwordHash 密码散列
     * @param displayName  展示名称
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO sys_user (
                id, username, password_hash, display_name, password_change_required
            ) VALUES (
                #{id}, #{username}, #{passwordHash}, #{displayName}, TRUE
            )
            """)
    int insertBootstrapAdmin(
            @Param("id") UUID id,
            @Param("username") String username,
            @Param("passwordHash") String passwordHash,
            @Param("displayName") String displayName
    );

    /**
     * 更新用户最近登录时间。
     *
     * @param userId 用户标识
     * @return 影响行数
     */
    @Update("""
            UPDATE sys_user
            SET last_login_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP,
                version = version + 1
            WHERE id = #{userId}
            """)
    int updateLastLoginAt(@Param("userId") UUID userId);

    /**
     * 修改密码散列并清除首次改密标记。
     *
     * @param userId       用户标识
     * @param passwordHash 新密码散列
     * @return 影响行数
     */
    @Update("""
            UPDATE sys_user
            SET password_hash = #{passwordHash},
                password_change_required = FALSE,
                updated_at = CURRENT_TIMESTAMP,
                version = version + 1
            WHERE id = #{userId}
            """)
    int updatePassword(
            @Param("userId") UUID userId,
            @Param("passwordHash") String passwordHash
    );

    /**
     * 追加一条不可变的登录审计记录。
     *
     * @param id               日志标识
     * @param userId           已识别的用户标识，账号不存在时为空
     * @param usernameSnapshot 登录名快照
     * @param eventType        登录事件类型
     * @param result           处理结果
     * @param errorCode        失败错误码
     * @param requestId        请求标识
     * @param traceId          HTTP 链路标识
     * @param clientIp         客户端地址
     * @param userAgent        客户端标识
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO sys_login_log (
                id, user_id, username_snapshot, event_type,
                result, error_code, request_id, trace_id, client_ip, user_agent
            ) VALUES (
                #{id}, #{userId}, #{usernameSnapshot}, #{eventType},
                #{result}, #{errorCode}, #{requestId}, #{traceId},
                CAST(#{clientIp} AS inet), #{userAgent}
            )
            """)
    int insertLoginLog(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("usernameSnapshot") String usernameSnapshot,
            @Param("eventType") String eventType,
            @Param("result") String result,
            @Param("errorCode") String errorCode,
            @Param("requestId") String requestId,
            @Param("traceId") String traceId,
            @Param("clientIp") String clientIp,
            @Param("userAgent") String userAgent
    );

    /**
     * 记录一次成功的密码修改操作。
     *
     * @param id               日志标识
     * @param userId           用户标识
     * @param usernameSnapshot 登录名快照
     * @param requestId        请求标识
     * @param traceId          HTTP 链路标识
     * @param clientIp         客户端地址
     * @param userAgent        客户端标识
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO sys_operation_log (
                id, operator_id, username_snapshot, module, action,
                resource_type, resource_id, request_id, trace_id,
                client_ip, user_agent, result, duration_ms
            ) VALUES (
                #{id}, #{userId}, #{usernameSnapshot}, 'AUTH', 'CHANGE_PASSWORD',
                'USER', #{userId}::text, #{requestId}, #{traceId},
                CAST(#{clientIp} AS inet), #{userAgent}, 'SUCCESS', 0
            )
            """)
    int insertPasswordChangeLog(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("usernameSnapshot") String usernameSnapshot,
            @Param("requestId") String requestId,
            @Param("traceId") String traceId,
            @Param("clientIp") String clientIp,
            @Param("userAgent") String userAgent
    );

    /**
     * 获取用户所有启用角色编码。
     *
     * @param userId 用户标识
     * @return 角色编码列表
     */
    @Select("""
            SELECT DISTINCT role.code
            FROM sys_user_role user_role
            JOIN sys_user user_account ON user_account.id = user_role.user_id
            JOIN sys_role role ON role.id = user_role.role_id
            WHERE user_role.user_id = #{userId}
              AND user_account.status = 'ENABLED'
              AND role.status = 'ENABLED'
            ORDER BY role.code
            """)
    List<String> findRoleCodes(@Param("userId") UUID userId);

    /**
     * 获取用户通过启用角色关联到的所有启用权限字符串。
     *
     * @param userId 用户标识
     * @return 权限字符串列表
     */
    @Select("""
            SELECT DISTINCT menu.permission
            FROM sys_user_role user_role
            JOIN sys_user user_account ON user_account.id = user_role.user_id
            JOIN sys_role role ON role.id = user_role.role_id
            JOIN sys_role_menu role_menu ON role_menu.role_id = role.id
            JOIN sys_menu menu ON menu.id = role_menu.menu_id
            WHERE user_role.user_id = #{userId}
              AND user_account.status = 'ENABLED'
              AND role.status = 'ENABLED'
              AND menu.status = 'ENABLED'
              AND menu.permission IS NOT NULL
            ORDER BY menu.permission
            """)
    List<String> findPermissionCodes(@Param("userId") UUID userId);

    /**
     * 获取全部启用的菜单和按钮节点，供超级管理员构建导航树。
     *
     * @return 启用节点列表
     */
    @Select("""
            SELECT id, parent_id, name, type, path, component_key,
                   permission, icon, sort_order, visible
            FROM sys_menu
            WHERE status = 'ENABLED'
            ORDER BY sort_order, id
            """)
    List<MenuRecord> findAllEnabledMenus();

    /**
     * 获取用户经启用角色获授的菜单节点，并递归补齐其启用祖先目录。
     *
     * @param userId 用户标识
     * @return 用户可访问节点列表
     */
    @Select("""
            WITH RECURSIVE granted_menu AS (
                SELECT DISTINCT menu.id, menu.parent_id, menu.name, menu.type,
                       menu.path, menu.component_key, menu.permission,
                       menu.icon, menu.sort_order, menu.visible
                FROM sys_user_role user_role
                JOIN sys_user user_account ON user_account.id = user_role.user_id
                JOIN sys_role role ON role.id = user_role.role_id
                JOIN sys_role_menu role_menu ON role_menu.role_id = role.id
                JOIN sys_menu menu ON menu.id = role_menu.menu_id
                WHERE user_role.user_id = #{userId}
                  AND user_account.status = 'ENABLED'
                  AND role.status = 'ENABLED'
                  AND menu.status = 'ENABLED'
            ), accessible_menu AS (
                SELECT * FROM granted_menu
                UNION
                SELECT parent.id, parent.parent_id, parent.name, parent.type,
                       parent.path, parent.component_key, parent.permission,
                       parent.icon, parent.sort_order, parent.visible
                FROM sys_menu parent
                JOIN accessible_menu child ON child.parent_id = parent.id
                WHERE parent.status = 'ENABLED'
            )
            SELECT DISTINCT id, parent_id, name, type, path, component_key,
                   permission, icon, sort_order, visible
            FROM accessible_menu
            ORDER BY sort_order, id
            """)
    List<MenuRecord> findEnabledMenusByUserId(@Param("userId") UUID userId);

    /**
     * 登录和授权所需的用户快照。
     *
     * @param id                     用户标识
     * @param username               登录名
     * @param passwordHash           密码散列
     * @param displayName            展示名称
     * @param status                 用户状态
     * @param passwordChangeRequired 是否必须修改初始密码
     */
    record AuthUserRecord(
            UUID id,
            String username,
            String passwordHash,
            String displayName,
            String status,
            boolean passwordChangeRequired
    ) {
    }

    /**
     * 菜单、页面或按钮节点的持久化投影。
     *
     * @param id           节点标识
     * @param parentId     父节点标识
     * @param name         展示名称
     * @param type         DIRECTORY、MENU 或 BUTTON
     * @param path         前端路由路径
     * @param componentKey 前端组件白名单键
     * @param permission   权限码
     * @param icon         图标键
     * @param sortOrder    同级顺序
     * @param visible      是否在菜单中可见
     */
    record MenuRecord(
            UUID id,
            UUID parentId,
            String name,
            String type,
            String path,
            String componentKey,
            String permission,
            String icon,
            int sortOrder,
            boolean visible
    ) {
    }
}
