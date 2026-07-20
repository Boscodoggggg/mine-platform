package com.bosco.mine.server.modules.alarm.repository;

import com.bosco.mine.messaging.alarm.AlarmRaisedPayload;
import com.bosco.mine.messaging.alarm.AlarmRecoveredPayload;
import com.bosco.mine.server.modules.alarm.domain.AlarmStatus;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 提供告警 Inbox、乱序聚合、查询和人工处置所需的数据库操作。
 */
@Repository
public class AlarmEventRepository {
    private static final String CONSUMER_NAME = "mine-server-alarm-event";

    private final AlarmEventMapper mapper;

    /**
     * 创建告警仓储。
     *
     * @param mapper 告警数据访问接口
     */
    public AlarmEventRepository(AlarmEventMapper mapper) {
        this.mapper = mapper;
    }

    /** 登记告警消息 Inbox 幂等记录。 */
    public boolean recordInbox(UUID eventId) {
        return mapper.recordInbox(CONSUMER_NAME, eventId) == 1;
    }

    /** 对告警周期获取事务级排他锁，保护不存在记录的并发首写。 */
    public void lockOccurrence(UUID alarmId) {
        mapper.lockOccurrence("alarm:" + alarmId);
    }

    /** 查询设备当前所属产品编码，用于拒绝伪造或过期的身份。 */
    public Optional<String> findDeviceProductKey(UUID deviceId) {
        return mapper.findDeviceProductKey(deviceId);
    }

    /** 锁定并读取告警聚合。 */
    public Optional<AlarmEventRecord> lockById(UUID alarmId) {
        return mapper.lockById(alarmId);
    }

    /** 读取告警详情。 */
    public Optional<AlarmEventRecord> findById(UUID alarmId) {
        return mapper.findById(alarmId);
    }

    /** 首次以触发事实创建告警周期。 */
    public void insertRaised(UUID eventId, AlarmRaisedPayload payload, String evidenceJson, Instant now) {
        mapper.insertRaised(eventId, payload, evidenceJson, now);
    }

    /** 首次以恢复事实创建告警周期，等待迟到的触发证据补齐。 */
    public void insertRecovered(
            UUID eventId,
            AlarmRecoveredPayload payload,
            String evidenceJson,
            Instant now
    ) {
        mapper.insertRecovered(eventId, payload, evidenceJson, now);
    }

    /** 补齐恢复先到场景中的触发事实。 */
    public void supplementRaised(UUID alarmId, UUID eventId, String evidenceJson, Instant now) {
        requireChanged(mapper.supplementRaised(alarmId, eventId, evidenceJson, now),
                "补齐告警触发事实");
    }

    /** 为已触发告警追加恢复事实。 */
    public void applyRecovery(
            UUID alarmId,
            UUID eventId,
            Instant recoveredAt,
            String evidenceJson,
            String recoveryReason,
            Instant now
    ) {
        requireChanged(mapper.applyRecovery(
                alarmId, eventId, recoveredAt, evidenceJson, recoveryReason, now),
                "追加告警恢复事实");
    }

    /** 按查询条件统计告警数量。 */
    public long count(AlarmQuery query) {
        return mapper.count(query, statusName(query));
    }

    /** 按固定告警等级统计尚未恢复的活动告警数量。 */
    public long countActiveBySeverity(String severity) {
        if (!java.util.Set.of("INFO", "WARNING", "CRITICAL").contains(severity)) {
            throw new IllegalArgumentException("活动告警指标等级不受支持");
        }
        return mapper.countActiveBySeverity(severity);
    }

    /** 按时间倒序分页查询告警。 */
    public List<AlarmEventRecord> find(AlarmQuery query, int page, int size) {
        return mapper.find(query, statusName(query), (long) (page - 1) * size, size);
    }

    /** 按告警和幂等键读取既有确认事实。 */
    public Optional<AcknowledgementRecord> findAcknowledgement(UUID alarmId, String idempotencyKey) {
        return mapper.findAcknowledgement(alarmId, idempotencyKey);
    }

    /** 保存唯一确认事实。 */
    public void insertAcknowledgement(
            UUID id,
            UUID alarmId,
            UUID operatorId,
            String note,
            String idempotencyKey,
            Instant acknowledgedAt
    ) {
        mapper.insertAcknowledgement(id, alarmId, operatorId, note, idempotencyKey, acknowledgedAt);
    }

    /** 以版本条件写入首次确认事实。 */
    public int acknowledge(
            UUID alarmId,
            UUID operatorId,
            String note,
            Instant acknowledgedAt,
            long expectedVersion
    ) {
        return mapper.acknowledge(alarmId, operatorId, note, acknowledgedAt, expectedVersion);
    }

    /** 以版本条件关闭一个已经恢复的告警。 */
    public int close(
            UUID alarmId,
            UUID operatorId,
            String note,
            String idempotencyKey,
            Instant closedAt,
            long expectedVersion
    ) {
        return mapper.close(
                alarmId, operatorId, note, idempotencyKey, closedAt, expectedVersion);
    }

    private static void requireChanged(int changed, String action) {
        if (changed != 1) {
            throw new IllegalStateException(action + "时告警并发状态已变化");
        }
    }

    private static String statusName(AlarmQuery query) {
        if (query.status() == null) {
            return null;
        }
        return switch (query.status()) {
            case ACTIVE_UNACKNOWLEDGED, ACTIVE_ACKNOWLEDGED,
                 RECOVERED_UNACKNOWLEDGED, RECOVERED_ACKNOWLEDGED, CLOSED ->
                    query.status().name();
        };
    }

    /** 告警查询条件。 */
    public record AlarmQuery(
            Boolean active,
            Boolean closed,
            AlarmStatus status,
            String severity,
            UUID deviceId,
            String deviceKeyword,
            String ruleKey,
            Instant from,
            Instant to
    ) {
    }

    /** 告警数据库快照。 */
    public record AlarmEventRecord(
            UUID id, UUID deviceId, String deviceCode, String deviceName,
            String productKey, String ruleKey, String ruleName, String sourceType,
            String severity, String propertyId, long policyRuntimeRevision,
            UUID policySnapshotId, String policyContentHash, Instant raisedAt,
            String raisedEvidenceJson, UUID raisedEventId, Instant recoveredAt,
            String recoveredEvidenceJson, UUID recoveredEventId, String recoveryReason,
            Instant acknowledgedAt, UUID acknowledgedBy, String acknowledgementNote,
            Instant closedAt, UUID closedBy, String closeNote, String closeIdempotencyKey,
            long version
    ) {
    }

    /** 告警确认事实。 */
    public record AcknowledgementRecord(
            UUID id, UUID operatorId, String note, String idempotencyKey, Instant acknowledgedAt
    ) {
    }
}
