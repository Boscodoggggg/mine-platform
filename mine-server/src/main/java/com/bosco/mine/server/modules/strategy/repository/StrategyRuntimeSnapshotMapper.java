package com.bosco.mine.server.modules.strategy.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 策略运行增量快照、快照条目和可靠事件的数据访问接口。
 */
@Mapper
public interface StrategyRuntimeSnapshotMapper {

    /** 查询产品是否已经发布过指定策略类型。 */
    @Select("""
            SELECT EXISTS (
                SELECT 1
                  FROM dev_strategy_release
                 WHERE product_id = #{productId}
                   AND strategy_type = #{strategyType}
            )
            """)
    boolean hasRelease(
            @Param("productId") UUID productId,
            @Param("strategyType") String strategyType
    );

    /** 计算产品和策略类型内下一个运行修订号。 */
    @Select("""
            SELECT COALESCE(MAX(runtime_revision), 0) + 1
              FROM dev_strategy_runtime_snapshot
             WHERE product_id = #{productId}
               AND strategy_type = #{strategyType}
            """)
    long nextRuntimeRevision(
            @Param("productId") UUID productId,
            @Param("strategyType") String strategyType
    );

    /** 写入不可变运行增量快照头。 */
    @Insert("""
            INSERT INTO dev_strategy_runtime_snapshot (
                id, product_id, strategy_type, runtime_revision,
                change_reason, item_count, content_hash
            ) VALUES (
                #{id}, #{productId}, #{strategyType}, #{runtimeRevision},
                #{changeReason}, #{itemCount}, #{contentHash}
            )
            """)
    int insertSnapshot(
            @Param("id") UUID id,
            @Param("productId") UUID productId,
            @Param("strategyType") String strategyType,
            @Param("runtimeRevision") long runtimeRevision,
            @Param("changeReason") String changeReason,
            @Param("itemCount") int itemCount,
            @Param("contentHash") String contentHash
    );

    /** 批量写入稳定排序的单设备快照条目。 */
    @Insert("""
            <script>
            INSERT INTO dev_strategy_runtime_snapshot_item (
                snapshot_id, device_id, values_json, content_hash
            ) VALUES
            <foreach collection="items" item="item" separator=",">
                (#{item.snapshotId}, #{item.deviceId},
                 CAST(#{item.valuesJson} AS JSONB), #{item.contentHash})
            </foreach>
            </script>
            """)
    int insertItems(@Param("items") List<SnapshotItemWrite> items);

    /** 在快照事务中写入只携带定位、修订和哈希的 Outbox 事件。 */
    @Insert("""
            INSERT INTO sys_outbox_event (
                id, aggregate_type, aggregate_id, event_type, schema_version, payload, headers
            ) VALUES (
                #{eventId}, 'STRATEGY_RUNTIME_SNAPSHOT', #{snapshotId},
                'strategy.runtime.changed', 1,
                CAST(#{payloadJson} AS JSONB), CAST(#{headersJson} AS JSONB)
            )
            """)
    int insertOutbox(
            @Param("eventId") UUID eventId,
            @Param("snapshotId") UUID snapshotId,
            @Param("payloadJson") String payloadJson,
            @Param("headersJson") String headersJson
    );

    /** 按标识查询不可变快照头。 */
    @Select("""
            SELECT id, product_id AS productId, strategy_type AS strategyType,
                   runtime_revision AS runtimeRevision, change_reason AS changeReason,
                   item_count AS itemCount, content_hash AS contentHash, created_at AS createdAt
              FROM dev_strategy_runtime_snapshot
             WHERE id = #{snapshotId}
            """)
    Optional<SnapshotRecord> findSnapshot(@Param("snapshotId") UUID snapshotId);

    /** 使用设备标识游标分页读取不可变快照条目。 */
    @Select("""
            <script>
            SELECT snapshot_id AS snapshotId, device_id AS deviceId,
                   values_json::TEXT AS valuesJson, content_hash AS contentHash
              FROM dev_strategy_runtime_snapshot_item
             WHERE snapshot_id = #{snapshotId}
            <if test="afterDeviceId != null">
               AND device_id &gt; #{afterDeviceId}
            </if>
             ORDER BY device_id
             LIMIT #{limit}
            </script>
            """)
    List<SnapshotItemRecord> findItems(
            @Param("snapshotId") UUID snapshotId,
            @Param("afterDeviceId") UUID afterDeviceId,
            @Param("limit") int limit
    );

    /** 待批量写入的单设备快照条目。 */
    record SnapshotItemWrite(
            UUID snapshotId,
            UUID deviceId,
            String valuesJson,
            String contentHash
    ) {
    }

    /** 不可变策略运行快照头。 */
    record SnapshotRecord(
            UUID id,
            UUID productId,
            String strategyType,
            long runtimeRevision,
            String changeReason,
            int itemCount,
            String contentHash,
            Instant createdAt
    ) {
    }

    /** 不可变策略运行快照条目。 */
    record SnapshotItemRecord(
            UUID snapshotId,
            UUID deviceId,
            String valuesJson,
            String contentHash
    ) {
    }
}
