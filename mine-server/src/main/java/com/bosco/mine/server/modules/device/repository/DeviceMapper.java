package com.bosco.mine.server.modules.device.repository;

import com.bosco.mine.server.modules.device.model.Device;
import com.bosco.mine.server.modules.device.model.Product;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Optional;
import java.util.UUID;

/**
 * 产品、设备和设备凭据的数据访问接口。
 */
@Mapper
public interface DeviceMapper {

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
}
