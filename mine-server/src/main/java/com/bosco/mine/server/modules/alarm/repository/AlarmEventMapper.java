package com.bosco.mine.server.modules.alarm.repository;

import com.bosco.mine.messaging.alarm.AlarmRaisedPayload;
import com.bosco.mine.messaging.alarm.AlarmRecoveredPayload;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 告警事件、消费幂等事实和人工处置事实的数据访问接口。
 */
@Mapper
public interface AlarmEventMapper {

    /** 登记告警消费幂等事实。 */
    int recordInbox(@Param("consumerName") String consumerName, @Param("eventId") UUID eventId);

    /** 获取告警周期事务级排他锁。 */
    Object lockOccurrence(@Param("lockKey") String lockKey);

    /** 查询设备当前所属产品编码。 */
    Optional<String> findDeviceProductKey(@Param("deviceId") UUID deviceId);

    /** 锁定并读取告警聚合。 */
    Optional<AlarmEventRepository.AlarmEventRecord> lockById(@Param("alarmId") UUID alarmId);

    /** 读取告警详情。 */
    Optional<AlarmEventRepository.AlarmEventRecord> findById(@Param("alarmId") UUID alarmId);

    /** 首次以触发事实创建告警周期。 */
    int insertRaised(
            @Param("eventId") UUID eventId,
            @Param("payload") AlarmRaisedPayload payload,
            @Param("evidenceJson") String evidenceJson,
            @Param("now") Instant now);

    /** 首次以恢复事实创建告警周期。 */
    int insertRecovered(
            @Param("eventId") UUID eventId,
            @Param("payload") AlarmRecoveredPayload payload,
            @Param("evidenceJson") String evidenceJson,
            @Param("now") Instant now);

    /** 补齐恢复先到场景中的触发事实。 */
    int supplementRaised(
            @Param("alarmId") UUID alarmId,
            @Param("eventId") UUID eventId,
            @Param("evidenceJson") String evidenceJson,
            @Param("now") Instant now);

    /** 为已触发告警追加恢复事实。 */
    int applyRecovery(
            @Param("alarmId") UUID alarmId,
            @Param("eventId") UUID eventId,
            @Param("recoveredAt") Instant recoveredAt,
            @Param("evidenceJson") String evidenceJson,
            @Param("recoveryReason") String recoveryReason,
            @Param("now") Instant now);

    /** 按查询条件统计告警数量。 */
    long count(
            @Param("query") AlarmEventRepository.AlarmQuery query,
            @Param("status") String status);

    /** 按固定等级统计活动告警数量。 */
    long countActiveBySeverity(@Param("severity") String severity);

    /** 按时间倒序分页查询告警。 */
    List<AlarmEventRepository.AlarmEventRecord> find(
            @Param("query") AlarmEventRepository.AlarmQuery query,
            @Param("status") String status,
            @Param("offset") long offset,
            @Param("limit") int limit);

    /** 按告警和幂等键读取既有确认事实。 */
    Optional<AlarmEventRepository.AcknowledgementRecord> findAcknowledgement(
            @Param("alarmId") UUID alarmId,
            @Param("idempotencyKey") String idempotencyKey);

    /** 保存唯一确认事实。 */
    int insertAcknowledgement(
            @Param("id") UUID id,
            @Param("alarmId") UUID alarmId,
            @Param("operatorId") UUID operatorId,
            @Param("note") String note,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("acknowledgedAt") Instant acknowledgedAt);

    /** 以版本条件写入首次确认事实。 */
    int acknowledge(
            @Param("alarmId") UUID alarmId,
            @Param("operatorId") UUID operatorId,
            @Param("note") String note,
            @Param("acknowledgedAt") Instant acknowledgedAt,
            @Param("expectedVersion") long expectedVersion);

    /** 以版本条件关闭一个已经恢复的告警。 */
    int close(
            @Param("alarmId") UUID alarmId,
            @Param("operatorId") UUID operatorId,
            @Param("note") String note,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("closedAt") Instant closedAt,
            @Param("expectedVersion") long expectedVersion);
}
