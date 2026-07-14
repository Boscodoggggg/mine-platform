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
}
