package com.bosco.mine.server.modules.system.repository;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 菜单、页面和按钮权限节点的数据访问接口。
 */
@Mapper
public interface MenuManagementMapper {

    /**
     * 查询完整菜单列表。
     *
     * @return 按排序值排列的菜单节点
     */
    @Select("""
            SELECT id, parent_id AS parentId, name, type, path,
                   component_key AS componentKey, permission, icon,
                   sort_order AS sortOrder, visible, status,
                   i18n_key AS i18nKey, protected_node AS protectedNode, version
            FROM sys_menu
            ORDER BY sort_order, id
            """)
    List<MenuRecord> findAllMenus();

    /**
     * 按父节点和筛选条件统计菜单树表格节点。
     *
     * @param parentId 父节点标识
     * @param keyword  名称关键词
     * @param type     菜单类型
     * @param status   菜单状态
     * @return 符合条件的直接子节点数量
     */
    @Select("""
            <script>
            WITH RECURSIVE matched AS (
                SELECT id, parent_id
                FROM sys_menu
                WHERE type &lt;&gt; 'BUTTON'
                <if test="keyword != null and keyword != ''">
                  AND name ILIKE CONCAT('%', #{keyword}, '%')
                </if>
                <if test="type != null and type != ''">
                  AND type = #{type}
                </if>
                <if test="status != null and status != ''">
                  AND status = #{status}
                </if>
                UNION
                SELECT parent.id, parent.parent_id
                FROM sys_menu parent
                JOIN matched child ON child.parent_id = parent.id
                WHERE parent.type &lt;&gt; 'BUTTON'
            )
            SELECT COUNT(*)
            FROM sys_menu menu
            WHERE menu.type &lt;&gt; 'BUTTON'
              AND menu.id IN (SELECT id FROM matched)
            <choose>
              <when test="parentId == null">AND menu.parent_id IS NULL</when>
              <otherwise>AND menu.parent_id = #{parentId}</otherwise>
            </choose>
            </script>
            """)
    long countMenuItems(
            @Param("parentId") UUID parentId,
            @Param("keyword") String keyword,
            @Param("type") String type,
            @Param("status") String status
    );

    /**
     * 按父节点分页查询菜单树表格节点。
     *
     * @param parentId 父节点标识
     * @param keyword  名称关键词
     * @param type     菜单类型
     * @param status   菜单状态
     * @param offset   分页偏移量
     * @param limit    最大返回数量
     * @return 当前层级菜单节点
     */
    @Select("""
            <script>
            WITH RECURSIVE matched AS (
                SELECT id, parent_id
                FROM sys_menu
                WHERE type &lt;&gt; 'BUTTON'
                <if test="keyword != null and keyword != ''">
                  AND name ILIKE CONCAT('%', #{keyword}, '%')
                </if>
                <if test="type != null and type != ''">
                  AND type = #{type}
                </if>
                <if test="status != null and status != ''">
                  AND status = #{status}
                </if>
                UNION
                SELECT parent.id, parent.parent_id
                FROM sys_menu parent
                JOIN matched child ON child.parent_id = parent.id
                WHERE parent.type &lt;&gt; 'BUTTON'
            )
            SELECT menu.id, menu.parent_id AS parentId, menu.name, menu.type, menu.path,
                   menu.component_key AS componentKey, menu.permission, menu.icon,
                   menu.sort_order AS sortOrder, menu.visible, menu.status,
                   menu.i18n_key AS i18nKey, menu.protected_node AS protectedNode, menu.version,
                   EXISTS (
                       SELECT 1
                       FROM sys_menu child
                       WHERE child.parent_id = menu.id
                         AND child.type &lt;&gt; 'BUTTON'
                         AND child.id IN (SELECT id FROM matched)
                   ) AS hasChildren
            FROM sys_menu menu
            WHERE menu.type &lt;&gt; 'BUTTON'
              AND menu.id IN (SELECT id FROM matched)
            <choose>
              <when test="parentId == null">AND menu.parent_id IS NULL</when>
              <otherwise>AND menu.parent_id = #{parentId}</otherwise>
            </choose>
            ORDER BY menu.sort_order, menu.id
            OFFSET #{offset} LIMIT #{limit}
            </script>
            """)
    List<MenuListRecord> findMenuItems(
            @Param("parentId") UUID parentId,
            @Param("keyword") String keyword,
            @Param("type") String type,
            @Param("status") String status,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    /**
     * 查询页面菜单下的直接按钮权限。
     *
     * @param parentId 页面菜单标识
     * @return 按钮权限节点
     */
    @Select("""
            SELECT id, parent_id AS parentId, name, type, path,
                   component_key AS componentKey, permission, icon,
                   sort_order AS sortOrder, visible, status,
                   i18n_key AS i18nKey, protected_node AS protectedNode, version
            FROM sys_menu
            WHERE parent_id = #{parentId} AND type = 'BUTTON'
            ORDER BY sort_order, id
            """)
    List<MenuRecord> findButtonChildren(@Param("parentId") UUID parentId);

    /**
     * 按标识查询菜单节点。
     *
     * @param id 菜单标识
     * @return 菜单节点
     */
    @Select("""
            SELECT id, parent_id AS parentId, name, type, path,
                   component_key AS componentKey, permission, icon,
                   sort_order AS sortOrder, visible, status,
                   i18n_key AS i18nKey, protected_node AS protectedNode, version
            FROM sys_menu WHERE id = #{id}
            """)
    Optional<MenuRecord> findMenuById(@Param("id") UUID id);

    /**
     * 新增菜单节点。
     *
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO sys_menu (
                id, parent_id, name, type, path, component_key, permission, icon,
                sort_order, visible, status, i18n_key, created_by, updated_by
            ) VALUES (
                #{id}, #{parentId}, #{name}, #{type}, #{path}, #{componentKey}, #{permission}, #{icon},
                #{sortOrder}, #{visible}, #{status}, #{i18nKey}, #{operatorId}, #{operatorId}
            )
            """)
    int insertMenu(
            @Param("id") UUID id,
            @Param("parentId") UUID parentId,
            @Param("name") String name,
            @Param("type") String type,
            @Param("path") String path,
            @Param("componentKey") String componentKey,
            @Param("permission") String permission,
            @Param("icon") String icon,
            @Param("sortOrder") int sortOrder,
            @Param("visible") boolean visible,
            @Param("status") String status,
            @Param("i18nKey") String i18nKey,
            @Param("operatorId") UUID operatorId
    );

    /**
     * 使用乐观锁修改菜单节点。
     *
     * @return 影响行数
     */
    @Update("""
            UPDATE sys_menu
            SET parent_id = #{parentId}, name = #{name}, type = #{type}, path = #{path},
                component_key = #{componentKey}, permission = #{permission}, icon = #{icon},
                sort_order = #{sortOrder}, visible = #{visible}, status = #{status},
                i18n_key = #{i18nKey}, updated_at = CURRENT_TIMESTAMP,
                updated_by = #{operatorId}, version = version + 1
            WHERE id = #{id} AND version = #{version}
            """)
    int updateMenu(
            @Param("id") UUID id,
            @Param("parentId") UUID parentId,
            @Param("name") String name,
            @Param("type") String type,
            @Param("path") String path,
            @Param("componentKey") String componentKey,
            @Param("permission") String permission,
            @Param("icon") String icon,
            @Param("sortOrder") int sortOrder,
            @Param("visible") boolean visible,
            @Param("status") String status,
            @Param("i18nKey") String i18nKey,
            @Param("version") long version,
            @Param("operatorId") UUID operatorId
    );

    /**
     * 统计直接子节点数量。
     *
     * @param id 菜单标识
     * @return 子节点数量
     */
    @Select("SELECT COUNT(*) FROM sys_menu WHERE parent_id = #{id}")
    long countChildren(@Param("id") UUID id);

    /**
     * 统计角色引用数量。
     *
     * @param id 菜单标识
     * @return 角色引用数量
     */
    @Select("SELECT COUNT(*) FROM sys_role_menu WHERE menu_id = #{id}")
    long countRoleReferences(@Param("id") UUID id);

    /**
     * 删除菜单节点。
     *
     * @param id 菜单标识
     * @return 影响行数
     */
    @Delete("DELETE FROM sys_menu WHERE id = #{id}")
    int deleteMenu(@Param("id") UUID id);

    /**
     * 菜单管理投影。
     *
     * @param id            节点标识
     * @param parentId      父节点标识
     * @param name          名称
     * @param type          类型
     * @param path          路由
     * @param componentKey  组件键
     * @param permission    权限码
     * @param icon          图标
     * @param sortOrder     排序值
     * @param visible       是否显示
     * @param status        状态
     * @param i18nKey       国际化键
     * @param protectedNode 是否核心节点
     * @param version       乐观锁版本
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
            boolean visible,
            String status,
            String i18nKey,
            boolean protectedNode,
            long version
    ) {
    }

    /**
     * 菜单树表格按层查询投影。
     */
    record MenuListRecord(
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
            String i18nKey,
            boolean protectedNode,
            long version,
            boolean hasChildren
    ) {
    }
}
