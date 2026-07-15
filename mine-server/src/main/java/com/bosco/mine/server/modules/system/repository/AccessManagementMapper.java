package com.bosco.mine.server.modules.system.repository;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 用户、角色及其授权关系的管理数据访问接口。
 */
@Mapper
public interface AccessManagementMapper {

    /**
     * 新增用户。
     *
     * @param id           用户标识
     * @param username     登录名
     * @param passwordHash 密码散列
     * @param displayName  展示名称
     * @param status       用户状态
     * @param createdBy    创建人标识
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO sys_user (
                id, username, password_hash, display_name, status, created_by, updated_by
            ) VALUES (
                #{id}, #{username}, #{passwordHash}, #{displayName}, #{status}, #{createdBy}, #{createdBy}
            )
            """)
    int insertUser(
            @Param("id") UUID id,
            @Param("username") String username,
            @Param("passwordHash") String passwordHash,
            @Param("displayName") String displayName,
            @Param("status") String status,
            @Param("createdBy") UUID createdBy
    );

    /**
     * 判断登录名是否已存在。
     *
     * @param username 登录名
     * @return 是否已存在
     */
    @Select("SELECT EXISTS (SELECT 1 FROM sys_user WHERE username = #{username})")
    boolean usernameExists(@Param("username") String username);

    /**
     * 按条件统计用户。
     *
     * @param keyword 登录名或展示名称关键词
     * @param status  用户状态
     * @return 用户数量
     */
    @Select("""
            <script>
            SELECT COUNT(*)
            FROM sys_user
            WHERE 1 = 1
            <if test="keyword != null and keyword != ''">
              AND (username ILIKE CONCAT('%', #{keyword}, '%')
                   OR display_name ILIKE CONCAT('%', #{keyword}, '%'))
            </if>
            <if test="status != null and status != ''">
              AND status = #{status}
            </if>
            </script>
            """)
    long countUsers(
            @Param("keyword") String keyword,
            @Param("status") String status
    );

    /**
     * 分页查询用户。
     *
     * @param keyword 登录名或展示名称关键词
     * @param status  用户状态
     * @param offset  起始偏移
     * @param limit   最大返回数量
     * @return 用户列表
     */
    @Select("""
            <script>
            SELECT id, username, display_name AS displayName, status,
                   password_change_required AS passwordChangeRequired,
                   last_login_at AS lastLoginAt, created_at AS createdAt,
                   updated_at AS updatedAt, version
            FROM sys_user
            WHERE 1 = 1
            <if test="keyword != null and keyword != ''">
              AND (username ILIKE CONCAT('%', #{keyword}, '%')
                   OR display_name ILIKE CONCAT('%', #{keyword}, '%'))
            </if>
            <if test="status != null and status != ''">
              AND status = #{status}
            </if>
            ORDER BY created_at DESC, id
            OFFSET #{offset} LIMIT #{limit}
            </script>
            """)
    List<ManagedUserRecord> findUsers(
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    /**
     * 查询用户管理快照。
     *
     * @param userId 用户标识
     * @return 用户管理快照
     */
    @Select("""
            SELECT id, username, display_name AS displayName, status,
                   password_change_required AS passwordChangeRequired,
                   last_login_at AS lastLoginAt, created_at AS createdAt,
                   updated_at AS updatedAt, version
            FROM sys_user
            WHERE id = #{userId}
            """)
    Optional<ManagedUserRecord> findManagedUserById(@Param("userId") UUID userId);

    /**
     * 通过乐观锁更新用户基本信息。
     *
     * @param userId         用户标识
     * @param displayName    展示名称
     * @param status         用户状态
     * @param expectedVersion 期望版本号
     * @param updatedBy      更新人标识
     * @return 影响行数，零表示记录不存在或版本冲突
     */
    @Update("""
            UPDATE sys_user
            SET display_name = #{displayName},
                status = #{status},
                updated_at = CURRENT_TIMESTAMP,
                updated_by = #{updatedBy},
                version = version + 1
            WHERE id = #{userId}
              AND version = #{expectedVersion}
            """)
    int updateUser(
            @Param("userId") UUID userId,
            @Param("displayName") String displayName,
            @Param("status") String status,
            @Param("expectedVersion") long expectedVersion,
            @Param("updatedBy") UUID updatedBy
    );

    /**
     * 设置临时密码并要求用户下次登录修改密码。
     *
     * @param userId       用户标识
     * @param passwordHash 临时密码散列
     * @param updatedBy    更新人标识
     * @return 影响行数
     */
    @Update("""
            UPDATE sys_user
            SET password_hash = #{passwordHash},
                password_change_required = TRUE,
                updated_at = CURRENT_TIMESTAMP,
                updated_by = #{updatedBy},
                version = version + 1
            WHERE id = #{userId}
            """)
    int resetUserPassword(
            @Param("userId") UUID userId,
            @Param("passwordHash") String passwordHash,
            @Param("updatedBy") UUID updatedBy
    );

    /**
     * 新增角色。
     *
     * @param id          角色标识
     * @param code        角色编码
     * @param name        角色名称
     * @param status      角色状态
     * @param description 角色说明
     * @param createdBy   创建人标识
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO sys_role (
                id, code, name, status, description, created_by, updated_by
            ) VALUES (
                #{id}, #{code}, #{name}, #{status}, #{description}, #{createdBy}, #{createdBy}
            )
            """)
    int insertRole(
            @Param("id") UUID id,
            @Param("code") String code,
            @Param("name") String name,
            @Param("status") String status,
            @Param("description") String description,
            @Param("createdBy") UUID createdBy
    );

    /**
     * 判断角色编码是否已存在。
     *
     * @param code 角色编码
     * @return 是否已存在
     */
    @Select("SELECT EXISTS (SELECT 1 FROM sys_role WHERE code = #{code})")
    boolean roleCodeExists(@Param("code") String code);

    /**
     * 按条件统计角色。
     *
     * @param keyword 角色编码或名称关键词
     * @param status  角色状态
     * @return 角色数量
     */
    @Select("""
            <script>
            SELECT COUNT(*)
            FROM sys_role
            WHERE 1 = 1
            <if test="keyword != null and keyword != ''">
              AND (code ILIKE CONCAT('%', #{keyword}, '%')
                   OR name ILIKE CONCAT('%', #{keyword}, '%'))
            </if>
            <if test="status != null and status != ''">
              AND status = #{status}
            </if>
            </script>
            """)
    long countRoles(
            @Param("keyword") String keyword,
            @Param("status") String status
    );

    /**
     * 分页查询角色。
     *
     * @param keyword 角色编码或名称关键词
     * @param status  角色状态
     * @param offset  起始偏移
     * @param limit   最大返回数量
     * @return 角色列表
     */
    @Select("""
            <script>
            SELECT id, code, name, status, description,
                   created_at AS createdAt, updated_at AS updatedAt, version
            FROM sys_role
            WHERE 1 = 1
            <if test="keyword != null and keyword != ''">
              AND (code ILIKE CONCAT('%', #{keyword}, '%')
                   OR name ILIKE CONCAT('%', #{keyword}, '%'))
            </if>
            <if test="status != null and status != ''">
              AND status = #{status}
            </if>
            ORDER BY created_at DESC, id
            OFFSET #{offset} LIMIT #{limit}
            </script>
            """)
    List<ManagedRoleRecord> findRoles(
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    /**
     * 查询角色管理快照。
     *
     * @param roleId 角色标识
     * @return 角色管理快照
     */
    @Select("""
            SELECT id, code, name, status, description,
                   created_at AS createdAt, updated_at AS updatedAt, version
            FROM sys_role
            WHERE id = #{roleId}
            """)
    Optional<ManagedRoleRecord> findManagedRoleById(@Param("roleId") UUID roleId);

    /**
     * 通过乐观锁更新角色可变信息。
     *
     * @param roleId         角色标识
     * @param name           角色名称
     * @param status         角色状态
     * @param description    角色说明
     * @param expectedVersion 期望版本号
     * @param updatedBy      更新人标识
     * @return 影响行数，零表示记录不存在或版本冲突
     */
    @Update("""
            UPDATE sys_role
            SET name = #{name},
                status = #{status},
                description = #{description},
                updated_at = CURRENT_TIMESTAMP,
                updated_by = #{updatedBy},
                version = version + 1
            WHERE id = #{roleId}
              AND version = #{expectedVersion}
            """)
    int updateRole(
            @Param("roleId") UUID roleId,
            @Param("name") String name,
            @Param("status") String status,
            @Param("description") String description,
            @Param("expectedVersion") long expectedVersion,
            @Param("updatedBy") UUID updatedBy
    );

    /**
     * 仅当角色尚未分配给用户时删除角色。
     *
     * @param roleId 角色标识
     * @return 影响行数
     */
    @Delete("""
            DELETE FROM sys_role role
            WHERE role.id = #{roleId}
              AND NOT EXISTS (
                  SELECT 1 FROM sys_user_role user_role WHERE user_role.role_id = role.id
              )
            """)
    int deleteUnusedRole(@Param("roleId") UUID roleId);

    /**
     * 新增用户角色关联。
     *
     * @param id        关联标识
     * @param userId    用户标识
     * @param roleId    角色标识
     * @param createdBy 创建人标识
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO sys_user_role (id, user_id, role_id, created_by)
            VALUES (#{id}, #{userId}, #{roleId}, #{createdBy})
            """)
    int insertUserRole(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("roleId") UUID roleId,
            @Param("createdBy") UUID createdBy
    );

    /**
     * 查询用户已经分配的角色标识。
     *
     * @param userId 用户标识
     * @return 角色标识列表
     */
    @Select("""
            SELECT role_id
            FROM sys_user_role
            WHERE user_id = #{userId}
            ORDER BY role_id
            """)
    @Results(@Result(
            column = "role_id",
            javaType = UUID.class,
            typeHandler = PostgreSqlUuidTypeHandler.class
    ))
    List<UUID> findRoleIdsByUserId(@Param("userId") UUID userId);

    /**
     * 统计正在使用角色的用户数。
     *
     * @param roleId 角色标识
     * @return 用户数量
     */
    @Select("SELECT COUNT(*) FROM sys_user_role WHERE role_id = #{roleId}")
    long countUsersByRoleId(@Param("roleId") UUID roleId);

    /**
     * 统计给定角色标识中真实存在且启用的数量。
     *
     * @param roleIds 角色标识集合
     * @return 启用角色数量
     */
    @Select("""
            <script>
            SELECT COUNT(*)
            FROM sys_role
            WHERE status = 'ENABLED'
              AND id IN
            <foreach collection="roleIds" item="roleId" open="(" separator="," close=")">
                #{roleId,typeHandler=com.bosco.mine.server.modules.system.repository.PostgreSqlUuidTypeHandler}
            </foreach>
            </script>
            """)
    long countEnabledRoleIds(@Param("roleIds") List<UUID> roleIds);

    /**
     * 删除用户现有的全部角色关联。
     *
     * @param userId 用户标识
     * @return 影响行数
     */
    @Delete("DELETE FROM sys_user_role WHERE user_id = #{userId}")
    int deleteUserRoles(@Param("userId") UUID userId);

    /**
     * 查询全部菜单、页面和按钮节点；节点定义仅由迁移脚本维护。
     *
     * @return 完整节点列表
     */
    @Select("""
            SELECT id, parent_id AS parentId, name, type, path,
                   component_key AS componentKey, permission, icon,
                   sort_order AS sortOrder, visible, status
            FROM sys_menu
            ORDER BY sort_order, id
            """)
    List<ManagedMenuRecord> findAllMenus();

    /**
     * 统计给定菜单标识中真实存在的数量。
     *
     * @param menuIds 菜单标识集合
     * @return 存在的菜单数量
     */
    @Select("""
            <script>
            SELECT COUNT(*)
            FROM sys_menu
            WHERE id IN
            <foreach collection="menuIds" item="menuId" open="(" separator="," close=")">
                #{menuId,typeHandler=com.bosco.mine.server.modules.system.repository.PostgreSqlUuidTypeHandler}
            </foreach>
            </script>
            """)
    long countExistingMenuIds(@Param("menuIds") List<UUID> menuIds);

    /**
     * 统计给定菜单标识中真实存在且启用的数量。
     *
     * @param menuIds 菜单标识集合
     * @return 启用菜单数量
     */
    @Select("""
            <script>
            SELECT COUNT(*)
            FROM sys_menu
            WHERE status = 'ENABLED'
              AND id IN
            <foreach collection="menuIds" item="menuId" open="(" separator="," close=")">
                #{menuId,typeHandler=com.bosco.mine.server.modules.system.repository.PostgreSqlUuidTypeHandler}
            </foreach>
            </script>
            """)
    long countEnabledMenuIds(@Param("menuIds") List<UUID> menuIds);

    /**
     * 新增角色菜单关联。
     *
     * @param id        关联标识
     * @param roleId    角色标识
     * @param menuId    菜单标识
     * @param createdBy 创建人标识
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO sys_role_menu (id, role_id, menu_id, created_by)
            VALUES (#{id}, #{roleId}, #{menuId}, #{createdBy})
            """)
    int insertRoleMenu(
            @Param("id") UUID id,
            @Param("roleId") UUID roleId,
            @Param("menuId") UUID menuId,
            @Param("createdBy") UUID createdBy
    );

    /**
     * 查询角色已经分配的菜单标识。
     *
     * @param roleId 角色标识
     * @return 菜单标识列表
     */
    @Select("""
            SELECT menu_id
            FROM sys_role_menu
            WHERE role_id = #{roleId}
            ORDER BY menu_id
            """)
    @Results(@Result(
            column = "menu_id",
            javaType = UUID.class,
            typeHandler = PostgreSqlUuidTypeHandler.class
    ))
    List<UUID> findMenuIdsByRoleId(@Param("roleId") UUID roleId);

    /**
     * 删除角色现有的全部菜单关联。
     *
     * @param roleId 角色标识
     * @return 影响行数
     */
    @Delete("DELETE FROM sys_role_menu WHERE role_id = #{roleId}")
    int deleteRoleMenus(@Param("roleId") UUID roleId);

    /**
     * 追加成功的访问管理操作审计记录。
     *
     * @param id               日志标识
     * @param operatorId       操作者标识
     * @param usernameSnapshot 操作者登录名快照
     * @param action           操作类型
     * @param permission       所需权限码
     * @param resourceType     资源类型
     * @param resourceId       资源标识
     * @param requestId        请求标识
     * @param traceId          链路标识
     * @param clientIp         客户端地址
     * @param userAgent        客户端标识
     * @param beforeSummary    操作前脱敏摘要 JSON
     * @param afterSummary     操作后脱敏摘要 JSON
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO sys_operation_log (
                id, operator_id, username_snapshot, module, action, permission,
                resource_type, resource_id, request_id, trace_id,
                client_ip, user_agent, result, duration_ms,
                before_summary, after_summary
            ) VALUES (
                #{id}, #{operatorId}, #{usernameSnapshot}, 'SYSTEM_ACCESS', #{action}, #{permission},
                #{resourceType}, #{resourceId}, #{requestId}, #{traceId},
                CAST(#{clientIp} AS inet), #{userAgent}, 'SUCCESS', 0,
                CAST(#{beforeSummary} AS jsonb), CAST(#{afterSummary} AS jsonb)
            )
            """)
    int insertOperationLog(
            @Param("id") UUID id,
            @Param("operatorId") UUID operatorId,
            @Param("usernameSnapshot") String usernameSnapshot,
            @Param("action") String action,
            @Param("permission") String permission,
            @Param("resourceType") String resourceType,
            @Param("resourceId") String resourceId,
            @Param("requestId") String requestId,
            @Param("traceId") String traceId,
            @Param("clientIp") String clientIp,
            @Param("userAgent") String userAgent,
            @Param("beforeSummary") String beforeSummary,
            @Param("afterSummary") String afterSummary
    );

    /**
     * 用户管理列表投影。
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
     */
    record ManagedUserRecord(
            UUID id,
            String username,
            String displayName,
            String status,
            boolean passwordChangeRequired,
            Instant lastLoginAt,
            Instant createdAt,
            Instant updatedAt,
            long version
    ) {
    }

    /**
     * 角色管理列表投影。
     *
     * @param id          角色标识
     * @param code        角色编码
     * @param name        角色名称
     * @param status      角色状态
     * @param description 角色说明
     * @param createdAt   创建时间
     * @param updatedAt   更新时间
     * @param version     乐观锁版本号
     */
    record ManagedRoleRecord(
            UUID id,
            String code,
            String name,
            String status,
            String description,
            Instant createdAt,
            Instant updatedAt,
            long version
    ) {
    }

    /**
     * 授权管理使用的只读菜单节点投影。
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
     * @param visible      是否在导航中显示
     * @param status       节点状态
     */
    record ManagedMenuRecord(
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
            String status
    ) {
    }
}
