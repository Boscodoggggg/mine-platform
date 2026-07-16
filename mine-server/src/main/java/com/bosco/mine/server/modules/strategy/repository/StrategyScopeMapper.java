package com.bosco.mine.server.modules.strategy.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 设备组、历史成员关系及后续作用域策略的数据访问接口。
 */
@Mapper
public interface StrategyScopeMapper {

    /**
     * 锁定产品行，统一设备组与策略发布的并发顺序。
     *
     * @param productId 产品标识
     * @return 被锁定的产品标识
     */
    @Select("SELECT id FROM dev_product WHERE id = #{productId} FOR UPDATE")
    UUID lockProduct(@Param("productId") UUID productId);

    /**
     * 判断产品是否存在且处于启用状态。
     *
     * @param productId 产品标识
     * @return 产品是否可用于创建设备组
     */
    @Select("SELECT EXISTS (SELECT 1 FROM dev_product WHERE id = #{productId} AND status = 'ACTIVE')")
    boolean isActiveProduct(@Param("productId") UUID productId);

    /**
     * 统计产品下符合条件的设备组。
     *
     * @param productId 产品标识
     * @param keyword   设备组编码或名称关键词
     * @param status    设备组状态
     * @return 设备组数量
     */
    @Select("""
            <script>
            SELECT COUNT(*)
            FROM dev_device_group
            WHERE product_id = #{productId}
            <if test="keyword != null and keyword != ''">
              AND (group_key ILIKE CONCAT('%', #{keyword}, '%')
                   OR name ILIKE CONCAT('%', #{keyword}, '%'))
            </if>
            <if test="status != null and status != ''">
              AND status = #{status}
            </if>
            </script>
            """)
    long countGroups(
            @Param("productId") UUID productId,
            @Param("keyword") String keyword,
            @Param("status") String status
    );

    /**
     * 分页查询产品下的设备组及当前成员数量。
     *
     * @param productId 产品标识
     * @param keyword   设备组编码或名称关键词
     * @param status    设备组状态
     * @param offset    起始偏移
     * @param limit     最大返回数量
     * @return 设备组快照
     */
    @Select("""
            <script>
            SELECT device_group.id,
                   device_group.product_id AS productId,
                   device_group.group_key AS groupKey,
                   device_group.name,
                   device_group.priority,
                   device_group.status,
                   device_group.description,
                   device_group.archived_at AS archivedAt,
                   device_group.created_at AS createdAt,
                   device_group.updated_at AS updatedAt,
                   device_group.version,
                   (SELECT COUNT(*)
                      FROM dev_device_group_membership membership
                     WHERE membership.group_id = device_group.id
                       AND membership.effective_to IS NULL) AS memberCount
            FROM dev_device_group device_group
            WHERE device_group.product_id = #{productId}
            <if test="keyword != null and keyword != ''">
              AND (device_group.group_key ILIKE CONCAT('%', #{keyword}, '%')
                   OR device_group.name ILIKE CONCAT('%', #{keyword}, '%'))
            </if>
            <if test="status != null and status != ''">
              AND device_group.status = #{status}
            </if>
            ORDER BY device_group.priority DESC, device_group.name, device_group.id
            OFFSET #{offset} LIMIT #{limit}
            </script>
            """)
    List<DeviceGroupRecord> findGroups(
            @Param("productId") UUID productId,
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    /**
     * 按标识读取设备组快照。
     *
     * @param groupId 设备组标识
     * @return 设备组快照
     */
    @Select("""
            SELECT device_group.id,
                   device_group.product_id AS productId,
                   device_group.group_key AS groupKey,
                   device_group.name,
                   device_group.priority,
                   device_group.status,
                   device_group.description,
                   device_group.archived_at AS archivedAt,
                   device_group.created_at AS createdAt,
                   device_group.updated_at AS updatedAt,
                   device_group.version,
                   (SELECT COUNT(*)
                      FROM dev_device_group_membership membership
                     WHERE membership.group_id = device_group.id
                       AND membership.effective_to IS NULL) AS memberCount
            FROM dev_device_group device_group
            WHERE device_group.id = #{groupId}
            """)
    Optional<DeviceGroupRecord> findGroupById(@Param("groupId") UUID groupId);

    /**
     * 锁定并读取设备组，串行化成员替换和归档操作。
     *
     * @param groupId 设备组标识
     * @return 被锁定的设备组快照
     */
    @Select("""
            SELECT device_group.id,
                   device_group.product_id AS productId,
                   device_group.group_key AS groupKey,
                   device_group.name,
                   device_group.priority,
                   device_group.status,
                   device_group.description,
                   device_group.archived_at AS archivedAt,
                   device_group.created_at AS createdAt,
                   device_group.updated_at AS updatedAt,
                   device_group.version,
                   (SELECT COUNT(*)
                      FROM dev_device_group_membership membership
                     WHERE membership.group_id = device_group.id
                       AND membership.effective_to IS NULL) AS memberCount
              FROM dev_device_group device_group
             WHERE device_group.id = #{groupId}
             FOR UPDATE OF device_group
            """)
    Optional<DeviceGroupRecord> findGroupByIdForUpdate(@Param("groupId") UUID groupId);

    /**
     * 新增一个设备组。
     *
     * @param id          设备组标识
     * @param productId   产品标识
     * @param groupKey    设备组编码
     * @param name        设备组名称
     * @param priority    策略优先级
     * @param description 用途说明
     * @param operatorId  操作人标识
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO dev_device_group (
                id, product_id, group_key, name, priority, status, description, created_by, updated_by
            ) VALUES (
                #{id}, #{productId}, #{groupKey}, #{name}, #{priority}, 'ACTIVE', #{description},
                #{operatorId}, #{operatorId}
            )
            """)
    int insertGroup(
            @Param("id") UUID id,
            @Param("productId") UUID productId,
            @Param("groupKey") String groupKey,
            @Param("name") String name,
            @Param("priority") int priority,
            @Param("description") String description,
            @Param("operatorId") UUID operatorId
    );

    /**
     * 使用乐观锁更新设备组可变属性。
     *
     * @param groupId        设备组标识
     * @param name           设备组名称
     * @param priority       策略优先级
     * @param description    用途说明
     * @param expectedVersion 期望版本号
     * @param operatorId     操作人标识
     * @return 影响行数
     */
    @Update("""
            UPDATE dev_device_group
               SET name = #{name},
                   priority = #{priority},
                   description = #{description},
                   updated_at = CURRENT_TIMESTAMP,
                   updated_by = #{operatorId},
                   version = version + 1
             WHERE id = #{groupId}
               AND status = 'ACTIVE'
               AND version = #{expectedVersion}
            """)
    int updateGroup(
            @Param("groupId") UUID groupId,
            @Param("name") String name,
            @Param("priority") int priority,
            @Param("description") String description,
            @Param("expectedVersion") long expectedVersion,
            @Param("operatorId") UUID operatorId
    );

    /**
     * 在成员集合变化后推进设备组乐观锁版本。
     *
     * @param groupId        设备组标识
     * @param expectedVersion 期望版本号
     * @param operatorId     操作人标识
     * @return 影响行数
     */
    @Update("""
            UPDATE dev_device_group
               SET updated_at = CURRENT_TIMESTAMP,
                   updated_by = #{operatorId},
                   version = version + 1
             WHERE id = #{groupId}
               AND status = 'ACTIVE'
               AND version = #{expectedVersion}
            """)
    int touchGroup(
            @Param("groupId") UUID groupId,
            @Param("expectedVersion") long expectedVersion,
            @Param("operatorId") UUID operatorId
    );

    /**
     * 使用乐观锁归档设备组。
     *
     * @param groupId        设备组标识
     * @param expectedVersion 期望版本号
     * @param operatorId     操作人标识
     * @return 影响行数
     */
    @Update("""
            UPDATE dev_device_group
               SET status = 'ARCHIVED',
                   archived_at = CURRENT_TIMESTAMP,
                   updated_at = CURRENT_TIMESTAMP,
                   updated_by = #{operatorId},
                   version = version + 1
             WHERE id = #{groupId}
               AND status = 'ACTIVE'
               AND version = #{expectedVersion}
            """)
    int archiveGroup(
            @Param("groupId") UUID groupId,
            @Param("expectedVersion") long expectedVersion,
            @Param("operatorId") UUID operatorId
    );

    /**
     * 查询设备组当前成员标识。
     *
     * @param groupId 设备组标识
     * @return 当前成员标识
     */
    @Select("""
            SELECT device_id
              FROM dev_device_group_membership
             WHERE group_id = #{groupId}
               AND effective_to IS NULL
             ORDER BY effective_from, id
            """)
    List<UUID> findActiveMemberIds(@Param("groupId") UUID groupId);

    /**
     * 查询设备组当前成员详情。
     *
     * @param groupId 设备组标识
     * @return 当前成员详情
     */
    @Select("""
            SELECT device.id,
                   device.device_code AS deviceCode,
                   device.name,
                   device.status,
                   membership.effective_from AS effectiveFrom
              FROM dev_device_group_membership membership
              JOIN dev_device device ON device.id = membership.device_id
             WHERE membership.group_id = #{groupId}
               AND membership.effective_to IS NULL
             ORDER BY device.device_code, device.id
            """)
    List<DeviceGroupMemberRecord> findActiveMembers(@Param("groupId") UUID groupId);

    /**
     * 统计目标集合中属于指定产品且未归档的设备。
     *
     * @param productId 产品标识
     * @param deviceIds 设备标识集合
     * @return 符合条件的设备数量
     */
    @Select("""
            <script>
            SELECT COUNT(*)
              FROM dev_device
             WHERE product_id = #{productId}
               AND status &lt;&gt; 'ARCHIVED'
               AND id IN
               <foreach collection="deviceIds" item="deviceId" open="(" separator="," close=")">
                 #{deviceId}
               </foreach>
            </script>
            """)
    long countEligibleDevices(
            @Param("productId") UUID productId,
            @Param("deviceIds") List<UUID> deviceIds
    );

    /**
     * 批量新增当前设备组成员关系，避免大设备组逐条访问数据库。
     *
     * @param groupId     设备组标识
     * @param memberships 待新增成员关系
     * @param operatorId  操作人标识
     * @return 影响行数
     */
    @Insert("""
            <script>
            INSERT INTO dev_device_group_membership (id, group_id, device_id, created_by)
            VALUES
            <foreach collection="memberships" item="membership" separator=",">
              (#{membership.id}, #{groupId}, #{membership.deviceId}, #{operatorId})
            </foreach>
            </script>
            """)
    int insertMemberships(
            @Param("groupId") UUID groupId,
            @Param("memberships") List<MembershipInsert> memberships,
            @Param("operatorId") UUID operatorId
    );

    /**
     * 结束指定设备组成员关系并保留历史区间。
     *
     * @param groupId    设备组标识
     * @param deviceIds  待移除设备标识
     * @param operatorId 操作人标识
     * @return 影响行数
     */
    @Update("""
            <script>
            UPDATE dev_device_group_membership
               SET effective_to = CURRENT_TIMESTAMP,
                   ended_by = #{operatorId}
             WHERE group_id = #{groupId}
               AND effective_to IS NULL
               AND device_id IN
               <foreach collection="deviceIds" item="deviceId" open="(" separator="," close=")">
                 #{deviceId}
               </foreach>
            </script>
            """)
    int closeMemberships(
            @Param("groupId") UUID groupId,
            @Param("deviceIds") List<UUID> deviceIds,
            @Param("operatorId") UUID operatorId
    );

    /**
     * 结束设备组全部当前成员关系。
     *
     * @param groupId    设备组标识
     * @param operatorId 操作人标识
     * @return 影响行数
     */
    @Update("""
            UPDATE dev_device_group_membership
               SET effective_to = CURRENT_TIMESTAMP,
                   ended_by = #{operatorId}
             WHERE group_id = #{groupId}
               AND effective_to IS NULL
            """)
    int closeAllActiveMemberships(
            @Param("groupId") UUID groupId,
            @Param("operatorId") UUID operatorId
    );

    /**
     * 设备组持久化快照。
     *
     * @param id          设备组标识
     * @param productId   所属产品标识
     * @param groupKey    稳定设备组编码
     * @param name        设备组名称
     * @param priority    策略优先级
     * @param status      设备组状态
     * @param description 用途说明
     * @param archivedAt  归档时间
     * @param createdAt   创建时间
     * @param updatedAt   更新时间
     * @param version     乐观锁版本号
     * @param memberCount 当前成员数量
     */
    record DeviceGroupRecord(
            UUID id,
            UUID productId,
            String groupKey,
            String name,
            int priority,
            String status,
            String description,
            Instant archivedAt,
            Instant createdAt,
            Instant updatedAt,
            long version,
            long memberCount
    ) {
    }

    /**
     * 设备组当前成员持久化快照。
     *
     * @param id            设备标识
     * @param deviceCode    设备编码
     * @param name          设备名称
     * @param status        设备状态
     * @param effectiveFrom 加入设备组的生效时间
     */
    record DeviceGroupMemberRecord(
            UUID id,
            String deviceCode,
            String name,
            String status,
            Instant effectiveFrom
    ) {
    }

    /**
     * 设备组成员批量新增参数。
     *
     * @param id       成员关系标识
     * @param deviceId 设备标识
     */
    record MembershipInsert(UUID id, UUID deviceId) {
    }
}
