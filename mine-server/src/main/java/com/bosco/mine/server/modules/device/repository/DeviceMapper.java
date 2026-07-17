package com.bosco.mine.server.modules.device.repository;

import com.bosco.mine.server.modules.device.model.Device;
import com.bosco.mine.server.modules.device.model.DeviceCredential;
import com.bosco.mine.server.modules.device.model.Product;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 产品、设备和设备凭据的数据访问接口。
 */
@Mapper
public interface DeviceMapper {

    /**
     * 锁定产品行，统一设备生命周期和策略发布的并发加锁顺序。
     *
     * @param productId 产品标识
     * @return 被锁定的产品标识
     */
    @Select("SELECT id FROM dev_product WHERE id = #{productId} FOR UPDATE")
    UUID lockProduct(@Param("productId") UUID productId);

    /**
     * 新增产品。
     *
     * @param id         产品标识
     * @param productKey 产品编码
     * @param name       产品名称
     * @param deviceType 设备类型
     * @param status     产品状态
     * @param createdBy  创建人标识
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO dev_product (
                id, product_key, name, device_type, status, created_by, updated_by
            ) VALUES (
                #{id}, #{productKey}, #{name}, #{deviceType}, #{status}, #{createdBy}, #{createdBy}
            )
            """)
    int insertProduct(
            @Param("id") UUID id,
            @Param("productKey") String productKey,
            @Param("name") String name,
            @Param("deviceType") String deviceType,
            @Param("status") String status,
            @Param("createdBy") UUID createdBy
    );

    /**
     * 判断产品编码是否存在。
     *
     * @param productKey 产品编码
     * @return 是否存在
     */
    @Select("SELECT EXISTS (SELECT 1 FROM dev_product WHERE product_key = #{productKey})")
    boolean productKeyExists(@Param("productKey") String productKey);

    /**
     * 按条件统计产品。
     *
     * @param keyword 名称或编码关键词
     * @param status  产品状态
     * @return 产品数量
     */
    @Select("""
            <script>
            SELECT COUNT(*)
            FROM dev_product
            WHERE 1 = 1
            <if test="keyword != null and keyword != ''">
              AND (product_key ILIKE CONCAT('%', #{keyword}, '%')
                   OR name ILIKE CONCAT('%', #{keyword}, '%'))
            </if>
            <if test="status != null and status != ''">
              AND status = #{status}
            </if>
            </script>
            """)
    long countProducts(@Param("keyword") String keyword, @Param("status") String status);

    /**
     * 分页查询产品。
     *
     * @param keyword 名称或编码关键词
     * @param status  产品状态
     * @param offset  起始偏移
     * @param limit   最大返回数量
     * @return 产品列表
     */
    @Select("""
            <script>
            SELECT id, product_key AS productKey, name, device_type AS deviceType, status,
                   archived_at AS archivedAt, created_at AS createdAt,
                   updated_at AS updatedAt, version
            FROM dev_product
            WHERE 1 = 1
            <if test="keyword != null and keyword != ''">
              AND (product_key ILIKE CONCAT('%', #{keyword}, '%')
                   OR name ILIKE CONCAT('%', #{keyword}, '%'))
            </if>
            <if test="status != null and status != ''">
              AND status = #{status}
            </if>
            ORDER BY created_at DESC, id
            OFFSET #{offset} LIMIT #{limit}
            </script>
            """)
    List<Product> findProducts(
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    /**
     * 按标识查询产品。
     *
     * @param productId 产品标识
     * @return 产品快照
     */
    @Select("""
            SELECT id, product_key AS productKey, name, device_type AS deviceType, status,
                   archived_at AS archivedAt, created_at AS createdAt,
                   updated_at AS updatedAt, version
            FROM dev_product
            WHERE id = #{productId}
            """)
    Optional<Product> findProductById(@Param("productId") UUID productId);

    /**
     * 使用乐观锁修改产品可变信息。
     *
     * @param productId        产品标识
     * @param name             产品名称
     * @param deviceType       设备类型
     * @param status           产品状态
     * @param expectedVersion 期望版本号
     * @param updatedBy       更新人标识
     * @return 影响行数
     */
    @Update("""
            UPDATE dev_product
            SET name = #{name},
                device_type = #{deviceType},
                status = #{status},
                updated_at = CURRENT_TIMESTAMP,
                updated_by = #{updatedBy},
                version = version + 1
            WHERE id = #{productId}
              AND status <> 'ARCHIVED'
              AND version = #{expectedVersion}
            """)
    int updateProduct(
            @Param("productId") UUID productId,
            @Param("name") String name,
            @Param("deviceType") String deviceType,
            @Param("status") String status,
            @Param("expectedVersion") long expectedVersion,
            @Param("updatedBy") UUID updatedBy
    );

    /**
     * 归档产品并保留台账。
     *
     * @param productId       产品标识
     * @param expectedVersion 期望版本号
     * @param updatedBy       更新人标识
     * @return 影响行数
     */
    @Update("""
            UPDATE dev_product
            SET status = 'ARCHIVED',
                archived_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP,
                updated_by = #{updatedBy},
                version = version + 1
            WHERE id = #{productId}
              AND status <> 'ARCHIVED'
              AND version = #{expectedVersion}
            """)
    int archiveProduct(
            @Param("productId") UUID productId,
            @Param("expectedVersion") long expectedVersion,
            @Param("updatedBy") UUID updatedBy
    );

    /**
     * 新增设备。
     *
     * @param id         设备标识
     * @param productId  产品标识
     * @param deviceCode 设备编码
     * @param name       设备名称
     * @param status     设备状态
     * @param createdBy  创建人标识
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO dev_device (
                id, product_id, device_code, name, status, created_by, updated_by
            ) VALUES (
                #{id}, #{productId}, #{deviceCode}, #{name}, #{status}, #{createdBy}, #{createdBy}
            )
            """)
    int insertDevice(
            @Param("id") UUID id,
            @Param("productId") UUID productId,
            @Param("deviceCode") String deviceCode,
            @Param("name") String name,
            @Param("status") String status,
            @Param("createdBy") UUID createdBy
    );

    /**
     * 判断设备编码是否存在。
     *
     * @param deviceCode 设备编码
     * @return 是否存在
     */
    @Select("SELECT EXISTS (SELECT 1 FROM dev_device WHERE device_code = #{deviceCode})")
    boolean deviceCodeExists(@Param("deviceCode") String deviceCode);

    /**
     * 按条件统计设备。
     *
     * @param productId 产品标识筛选
     * @param keyword   名称或编码关键词
     * @param status    设备状态
     * @return 设备数量
     */
    @Select("""
            <script>
            SELECT COUNT(*)
            FROM dev_device
            WHERE 1 = 1
            <if test="productId != null">
              AND product_id = #{productId}
            </if>
            <if test="keyword != null and keyword != ''">
              AND (device_code ILIKE CONCAT('%', #{keyword}, '%')
                   OR name ILIKE CONCAT('%', #{keyword}, '%'))
            </if>
            <if test="status != null and status != ''">
              AND status = #{status}
            </if>
            </script>
            """)
    long countDevices(
            @Param("productId") UUID productId,
            @Param("keyword") String keyword,
            @Param("status") String status
    );

    /**
     * 分页查询设备。
     *
     * @param productId 产品标识筛选
     * @param keyword   名称或编码关键词
     * @param status    设备状态
     * @param offset    起始偏移
     * @param limit     最大返回数量
     * @return 设备列表
     */
    @Select("""
            <script>
            SELECT id, product_id AS productId, device_code AS deviceCode, name, status,
                   archived_at AS archivedAt, created_at AS createdAt,
                   updated_at AS updatedAt, version
            FROM dev_device
            WHERE 1 = 1
            <if test="productId != null">
              AND product_id = #{productId}
            </if>
            <if test="keyword != null and keyword != ''">
              AND (device_code ILIKE CONCAT('%', #{keyword}, '%')
                   OR name ILIKE CONCAT('%', #{keyword}, '%'))
            </if>
            <if test="status != null and status != ''">
              AND status = #{status}
            </if>
            ORDER BY created_at DESC, id
            OFFSET #{offset} LIMIT #{limit}
            </script>
            """)
    List<Device> findDevices(
            @Param("productId") UUID productId,
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    /**
     * 按标识查询设备，包括已归档设备。
     *
     * @param deviceId 设备标识
     * @return 设备快照
     */
    @Select("""
            SELECT id, product_id AS productId, device_code AS deviceCode, name, status,
                   archived_at AS archivedAt, created_at AS createdAt,
                   updated_at AS updatedAt, version
            FROM dev_device
            WHERE id = #{deviceId}
            """)
    Optional<Device> findDeviceById(@Param("deviceId") UUID deviceId);

    /**
     * 批量查询设备台账，包括已归档设备。
     *
     * @param deviceIds 设备标识集合
     * @return 已存在设备台账
     */
    @Select("""
            <script>
            SELECT id, product_id AS productId, device_code AS deviceCode, name, status,
                   archived_at AS archivedAt, created_at AS createdAt,
                   updated_at AS updatedAt, version
              FROM dev_device
             WHERE id IN
             <foreach collection="deviceIds" item="deviceId" open="(" separator="," close=")">
               #{deviceId}
             </foreach>
             ORDER BY id
            </script>
            """)
    List<Device> findDevicesByIds(@Param("deviceIds") List<UUID> deviceIds);

    /**
     * 按编码查询未归档设备。
     *
     * @param deviceCode 设备编码
     * @return 活动设备快照
     */
    @Select("""
            SELECT id, product_id AS productId, device_code AS deviceCode, name, status,
                   archived_at AS archivedAt, created_at AS createdAt,
                   updated_at AS updatedAt, version
            FROM dev_device
            WHERE device_code = #{deviceCode}
              AND status <> 'ARCHIVED'
            """)
    Optional<Device> findActiveDeviceByCode(@Param("deviceCode") String deviceCode);

    /**
     * 统计产品下尚未归档的设备。
     *
     * @param productId 产品标识
     * @return 未归档设备数量
     */
    @Select("""
            SELECT COUNT(*)
            FROM dev_device
            WHERE product_id = #{productId}
              AND status <> 'ARCHIVED'
            """)
    long countUnarchivedDevices(@Param("productId") UUID productId);

    /**
     * 使用乐观锁修改设备可变信息。
     *
     * @param deviceId        设备标识
     * @param name            设备名称
     * @param status          设备状态
     * @param expectedVersion 期望版本号
     * @param updatedBy      更新人标识
     * @return 影响行数
     */
    @Update("""
            UPDATE dev_device
            SET name = #{name},
                status = #{status},
                updated_at = CURRENT_TIMESTAMP,
                updated_by = #{updatedBy},
                version = version + 1
            WHERE id = #{deviceId}
              AND status <> 'ARCHIVED'
              AND version = #{expectedVersion}
            """)
    int updateDevice(
            @Param("deviceId") UUID deviceId,
            @Param("name") String name,
            @Param("status") String status,
            @Param("expectedVersion") long expectedVersion,
            @Param("updatedBy") UUID updatedBy
    );

    /**
     * 使用乐观锁归档设备并保留台账。
     *
     * @param deviceId       设备标识
     * @param expectedVersion 期望版本号
     * @param updatedBy      更新人标识
     * @return 影响行数
     */
    @Update("""
            UPDATE dev_device
            SET status = 'ARCHIVED',
                archived_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP,
                updated_by = #{updatedBy},
                version = version + 1
            WHERE id = #{deviceId}
              AND status <> 'ARCHIVED'
              AND version = #{expectedVersion}
            """)
    int archiveDevice(
            @Param("deviceId") UUID deviceId,
            @Param("expectedVersion") long expectedVersion,
            @Param("updatedBy") UUID updatedBy
    );

    /**
     * 保存设备凭据的不可逆散列。
     *
     * @param id         凭据标识
     * @param deviceId   设备标识
     * @param username   MQTT 用户名
     * @param secretHash 密钥散列
     * @param secretHint 密钥提示
     * @param topicRoot  设备独占主题根路径
     * @param createdBy  创建人标识
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO dev_device_credential (
                id, device_id, username, secret_hash, secret_hint, topic_root,
                created_by, updated_by
            ) VALUES (
                #{id}, #{deviceId}, #{username}, #{secretHash}, #{secretHint}, #{topicRoot},
                #{createdBy}, #{createdBy}
            )
            """)
    int insertCredential(
            @Param("id") UUID id,
            @Param("deviceId") UUID deviceId,
            @Param("username") String username,
            @Param("secretHash") String secretHash,
            @Param("secretHint") String secretHint,
            @Param("topicRoot") String topicRoot,
            @Param("createdBy") UUID createdBy
    );

    /**
     * 按标识查询设备凭据安全快照。
     *
     * @param credentialId 凭据标识
     * @return 不含明文密钥的凭据快照
     */
    @Select("""
            SELECT id, device_id AS deviceId, username, secret_hash AS secretHash,
                   secret_hint AS secretHint, topic_root AS topicRoot, status,
                   updated_at AS updatedAt, version
            FROM dev_device_credential
            WHERE id = #{credentialId}
            """)
    Optional<DeviceCredential> findCredentialById(@Param("credentialId") UUID credentialId);

    /**
     * 查询设备当前活动凭据安全快照。
     *
     * @param deviceId 设备标识
     * @return 不含明文密钥的活动凭据
     */
    @Select("""
            SELECT id, device_id AS deviceId, username, secret_hash AS secretHash,
                   secret_hint AS secretHint, topic_root AS topicRoot, status,
                   updated_at AS updatedAt, version
            FROM dev_device_credential
            WHERE device_id = #{deviceId} AND status = 'ACTIVE'
            ORDER BY created_at DESC
            LIMIT 1
            """)
    Optional<DeviceCredential> findActiveCredentialByDeviceId(@Param("deviceId") UUID deviceId);

    /**
     * 判断设备是否已有活动凭据。
     *
     * @param deviceId 设备标识
     * @return 是否已有活动凭据
     */
    @Select("""
            SELECT EXISTS (
                SELECT 1 FROM dev_device_credential
                WHERE device_id = #{deviceId} AND status = 'ACTIVE'
            )
            """)
    boolean activeCredentialExists(@Param("deviceId") UUID deviceId);

    /**
     * 使用乐观锁更新凭据密钥散列。
     *
     * @param credentialId   凭据标识
     * @param secretHash     新密钥散列
     * @param secretHint     新密钥提示
     * @param expectedVersion 期望版本号
     * @param updatedBy      更新人标识
     * @return 影响行数
     */
    @Update("""
            UPDATE dev_device_credential
            SET secret_hash = #{secretHash},
                secret_hint = #{secretHint},
                updated_at = CURRENT_TIMESTAMP,
                updated_by = #{updatedBy},
                version = version + 1
            WHERE id = #{credentialId}
              AND status = 'ACTIVE'
              AND version = #{expectedVersion}
            """)
    int rotateCredential(
            @Param("credentialId") UUID credentialId,
            @Param("secretHash") String secretHash,
            @Param("secretHint") String secretHint,
            @Param("expectedVersion") long expectedVersion,
            @Param("updatedBy") UUID updatedBy
    );

    /**
     * 使用乐观锁吊销活动凭据。
     *
     * @param credentialId    凭据标识
     * @param expectedVersion 期望版本号
     * @param updatedBy       更新人标识
     * @return 影响行数
     */
    @Update("""
            UPDATE dev_device_credential
            SET status = 'REVOKED',
                revoked_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP,
                updated_by = #{updatedBy},
                version = version + 1
            WHERE id = #{credentialId}
              AND status = 'ACTIVE'
              AND version = #{expectedVersion}
            """)
    int revokeCredential(
            @Param("credentialId") UUID credentialId,
            @Param("expectedVersion") long expectedVersion,
            @Param("updatedBy") UUID updatedBy
    );
}
