package com.bosco.mine.server.modules.device.repository;

import com.bosco.mine.server.modules.device.model.DeviceConnectionProfile;
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
 * 设备接入配置数据访问接口。
 */
@Mapper
public interface DeviceConnectionProfileMapper {

    /**
     * 按设备查询接入配置。
     *
     * @param deviceId 设备标识
     * @return 接入配置
     */
    @Select("""
            SELECT id, device_id AS deviceId, connection_mode AS connectionMode,
                   host, port, tls_enabled AS tlsEnabled, tls_server_name AS tlsServerName,
                   connect_timeout_millis AS connectTimeoutMillis,
                   keep_alive_seconds AS keepAliveSeconds,
                   offline_threshold_seconds AS offlineThresholdSeconds,
                   last_test_status AS lastTestStatus, last_test_at AS lastTestAt,
                   last_test_duration_millis AS lastTestDurationMillis,
                   last_test_message AS lastTestMessage,
                   last_test_stages::text AS lastTestStages,
                   created_at AS createdAt, updated_at AS updatedAt, version
            FROM dev_device_connection_profile
            WHERE device_id = #{deviceId}
            """)
    Optional<DeviceConnectionProfile> findByDeviceId(@Param("deviceId") UUID deviceId);

    /**
     * 批量查询设备接入配置。
     *
     * @param deviceIds 设备标识集合
     * @return 已配置设备的接入配置
     */
    @Select("""
            <script>
            SELECT id, device_id AS deviceId, connection_mode AS connectionMode,
                   host, port, tls_enabled AS tlsEnabled, tls_server_name AS tlsServerName,
                   connect_timeout_millis AS connectTimeoutMillis,
                   keep_alive_seconds AS keepAliveSeconds,
                   offline_threshold_seconds AS offlineThresholdSeconds,
                   last_test_status AS lastTestStatus, last_test_at AS lastTestAt,
                   last_test_duration_millis AS lastTestDurationMillis,
                   last_test_message AS lastTestMessage,
                   last_test_stages::text AS lastTestStages,
                   created_at AS createdAt, updated_at AS updatedAt, version
              FROM dev_device_connection_profile
             WHERE device_id IN
             <foreach collection="deviceIds" item="deviceId" open="(" separator="," close=")">
               #{deviceId}
             </foreach>
             ORDER BY device_id
            </script>
            """)
    List<DeviceConnectionProfile> findByDeviceIds(@Param("deviceIds") List<UUID> deviceIds);

    /**
     * 新增接入配置。
     *
     * @param profile 配置快照
     * @param createdBy 创建人
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO dev_device_connection_profile (
                id, device_id, connection_mode, host, port, tls_enabled, tls_server_name,
                connect_timeout_millis, keep_alive_seconds, offline_threshold_seconds,
                created_by, updated_by
            ) VALUES (
                #{profile.id}, #{profile.deviceId}, #{profile.connectionMode}, #{profile.host},
                #{profile.port}, #{profile.tlsEnabled}, #{profile.tlsServerName},
                #{profile.connectTimeoutMillis}, #{profile.keepAliveSeconds},
                #{profile.offlineThresholdSeconds},
                #{createdBy}, #{createdBy}
            )
            """)
    int insert(@Param("profile") DeviceConnectionProfile profile, @Param("createdBy") UUID createdBy);

    /**
     * 使用乐观锁修改接入配置并清空旧诊断结论。
     *
     * @param profile        新配置
     * @param expectedVersion 期望版本
     * @param updatedBy      修改人
     * @return 影响行数
     */
    @Update("""
            UPDATE dev_device_connection_profile
            SET connection_mode = #{profile.connectionMode}, host = #{profile.host}, port = #{profile.port},
                tls_enabled = #{profile.tlsEnabled}, tls_server_name = #{profile.tlsServerName},
                connect_timeout_millis = #{profile.connectTimeoutMillis},
                keep_alive_seconds = #{profile.keepAliveSeconds},
                offline_threshold_seconds = #{profile.offlineThresholdSeconds},
                last_test_status = 'NOT_TESTED', last_test_at = NULL,
                last_test_duration_millis = NULL, last_test_message = NULL,
                last_test_stages = '[]'::jsonb, updated_at = CURRENT_TIMESTAMP,
                updated_by = #{updatedBy}, version = version + 1
            WHERE device_id = #{profile.deviceId} AND version = #{expectedVersion}
            """)
    int update(
            @Param("profile") DeviceConnectionProfile profile,
            @Param("expectedVersion") long expectedVersion,
            @Param("updatedBy") UUID updatedBy
    );

    /**
     * 保存最近一次分层诊断结果。
     *
     * @param profileId       配置标识
     * @param status          总体状态
     * @param testedAt        诊断时间
     * @param durationMillis  总耗时
     * @param message         脱敏结论
     * @param stagesJson      阶段结果 JSON
     * @param expectedVersion 期望版本
     * @param updatedBy       操作人
     * @return 影响行数
     */
    @Update("""
            UPDATE dev_device_connection_profile
            SET last_test_status = #{status}, last_test_at = #{testedAt},
                last_test_duration_millis = #{durationMillis}, last_test_message = #{message},
                last_test_stages = CAST(#{stagesJson} AS jsonb),
                updated_at = CURRENT_TIMESTAMP, updated_by = #{updatedBy}, version = version + 1
            WHERE id = #{profileId} AND version = #{expectedVersion}
            """)
    int updateTestResult(
            @Param("profileId") UUID profileId,
            @Param("status") String status,
            @Param("testedAt") Instant testedAt,
            @Param("durationMillis") long durationMillis,
            @Param("message") String message,
            @Param("stagesJson") String stagesJson,
            @Param("expectedVersion") long expectedVersion,
            @Param("updatedBy") UUID updatedBy
    );
}
