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
}
