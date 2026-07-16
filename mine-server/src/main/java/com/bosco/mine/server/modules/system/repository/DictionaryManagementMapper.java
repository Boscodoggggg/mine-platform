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
 * 系统通用字典类型和数据项的数据访问接口。
 */
@Mapper
public interface DictionaryManagementMapper {

    /**
     * 查询全部字典类型。
     *
     * @return 字典类型列表
     */
    @Select("""
            SELECT id, code, name, status, description, built_in AS builtIn, version
            FROM sys_dict_type ORDER BY name, id
            """)
    List<DictTypeRecord> findAllTypes();

    /**
     * 按标识查询字典类型。
     */
    @Select("""
            SELECT id, code, name, status, description, built_in AS builtIn, version
            FROM sys_dict_type WHERE id = #{id}
            """)
    Optional<DictTypeRecord> findTypeById(@Param("id") UUID id);

    /**
     * 按编码查询字典类型。
     */
    @Select("""
            SELECT id, code, name, status, description, built_in AS builtIn, version
            FROM sys_dict_type WHERE code = #{code}
            """)
    Optional<DictTypeRecord> findTypeByCode(@Param("code") String code);

    /**
     * 新增字典类型。
     */
    @Insert("""
            INSERT INTO sys_dict_type (
                id, code, name, status, description, created_by, updated_by
            ) VALUES (
                #{id}, #{code}, #{name}, #{status}, #{description}, #{operatorId}, #{operatorId}
            )
            """)
    int insertType(
            @Param("id") UUID id,
            @Param("code") String code,
            @Param("name") String name,
            @Param("status") String status,
            @Param("description") String description,
            @Param("operatorId") UUID operatorId
    );

    /**
     * 使用乐观锁修改字典类型可变属性。
     */
    @Update("""
            UPDATE sys_dict_type
            SET name = #{name}, status = #{status}, description = #{description},
                updated_at = CURRENT_TIMESTAMP, updated_by = #{operatorId}, version = version + 1
            WHERE id = #{id} AND version = #{version}
            """)
    int updateType(
            @Param("id") UUID id,
            @Param("name") String name,
            @Param("status") String status,
            @Param("description") String description,
            @Param("version") long version,
            @Param("operatorId") UUID operatorId
    );

    /**
     * 统计类型下的数据项数量。
     */
    @Select("SELECT COUNT(*) FROM sys_dict_item WHERE type_id = #{typeId}")
    long countItems(@Param("typeId") UUID typeId);

    /**
     * 删除空字典类型。
     */
    @Delete("DELETE FROM sys_dict_type WHERE id = #{id}")
    int deleteType(@Param("id") UUID id);

    /**
     * 查询一个类型下的全部数据项。
     */
    @Select("""
            SELECT id, type_id AS typeId, value, label_zh AS labelZh, label_en AS labelEn,
                   semantic_type AS semanticType, sort_order AS sortOrder,
                   default_item AS defaultItem, status, version
            FROM sys_dict_item WHERE type_id = #{typeId}
            ORDER BY sort_order, id
            """)
    List<DictItemRecord> findItemsByTypeId(@Param("typeId") UUID typeId);

    /**
     * 查询一个启用字典类型的启用数据项。
     */
    @Select("""
            SELECT i.id, i.type_id AS typeId, i.value, i.label_zh AS labelZh, i.label_en AS labelEn,
                   i.semantic_type AS semanticType, i.sort_order AS sortOrder,
                   i.default_item AS defaultItem, i.status, i.version
            FROM sys_dict_item i
            JOIN sys_dict_type t ON t.id = i.type_id
            WHERE t.code = #{code} AND t.status = 'ENABLED' AND i.status = 'ENABLED'
            ORDER BY i.sort_order, i.id
            """)
    List<DictItemRecord> findEnabledItemsByCode(@Param("code") String code);

    /**
     * 按标识查询字典项。
     */
    @Select("""
            SELECT id, type_id AS typeId, value, label_zh AS labelZh, label_en AS labelEn,
                   semantic_type AS semanticType, sort_order AS sortOrder,
                   default_item AS defaultItem, status, version
            FROM sys_dict_item WHERE id = #{id}
            """)
    Optional<DictItemRecord> findItemById(@Param("id") UUID id);

    /**
     * 清除同一类型的其他启用默认项。
     */
    @Update("""
            UPDATE sys_dict_item
            SET default_item = FALSE, updated_at = CURRENT_TIMESTAMP, version = version + 1
            WHERE type_id = #{typeId} AND default_item = TRUE
              AND (CAST(#{excludeId} AS UUID) IS NULL OR id <> #{excludeId})
            """)
    int clearDefaultItem(@Param("typeId") UUID typeId, @Param("excludeId") UUID excludeId);

    /**
     * 新增字典项。
     */
    @Insert("""
            INSERT INTO sys_dict_item (
                id, type_id, value, label_zh, label_en, semantic_type,
                sort_order, default_item, status, created_by, updated_by
            ) VALUES (
                #{id}, #{typeId}, #{value}, #{labelZh}, #{labelEn}, #{semanticType},
                #{sortOrder}, #{defaultItem}, #{status}, #{operatorId}, #{operatorId}
            )
            """)
    int insertItem(
            @Param("id") UUID id,
            @Param("typeId") UUID typeId,
            @Param("value") String value,
            @Param("labelZh") String labelZh,
            @Param("labelEn") String labelEn,
            @Param("semanticType") String semanticType,
            @Param("sortOrder") int sortOrder,
            @Param("defaultItem") boolean defaultItem,
            @Param("status") String status,
            @Param("operatorId") UUID operatorId
    );

    /**
     * 使用乐观锁修改字典项可变属性。
     */
    @Update("""
            UPDATE sys_dict_item
            SET label_zh = #{labelZh}, label_en = #{labelEn}, semantic_type = #{semanticType},
                sort_order = #{sortOrder}, default_item = #{defaultItem}, status = #{status},
                updated_at = CURRENT_TIMESTAMP, updated_by = #{operatorId}, version = version + 1
            WHERE id = #{id} AND version = #{version}
            """)
    int updateItem(
            @Param("id") UUID id,
            @Param("labelZh") String labelZh,
            @Param("labelEn") String labelEn,
            @Param("semanticType") String semanticType,
            @Param("sortOrder") int sortOrder,
            @Param("defaultItem") boolean defaultItem,
            @Param("status") String status,
            @Param("version") long version,
            @Param("operatorId") UUID operatorId
    );

    /**
     * 删除字典项。
     */
    @Delete("DELETE FROM sys_dict_item WHERE id = #{id}")
    int deleteItem(@Param("id") UUID id);

    /**
     * 字典类型投影。
     */
    record DictTypeRecord(
            UUID id,
            String code,
            String name,
            String status,
            String description,
            boolean builtIn,
            long version
    ) {
    }

    /**
     * 字典数据项投影。
     */
    record DictItemRecord(
            UUID id,
            UUID typeId,
            String value,
            String labelZh,
            String labelEn,
            String semanticType,
            int sortOrder,
            boolean defaultItem,
            String status,
            long version
    ) {
    }
}
