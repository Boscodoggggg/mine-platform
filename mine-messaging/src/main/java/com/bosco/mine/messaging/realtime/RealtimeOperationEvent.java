package com.bosco.mine.messaging.realtime;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 生产运营事务提交并进入可靠 Outbox 后发布的矿区实时增量。
 *
 * @param eventId 运营 Outbox UUIDv7 事件标识
 * @param organizationId 所属组织标识
 * @param siteId 所属矿区标识
 * @param aggregateType 变更聚合类型
 * @param aggregateId 变更聚合标识
 * @param updateType 固定低基数更新类型
 * @param businessSequence 矿区内连续递增业务序号
 * @param occurredAt 业务 Outbox 创建时间
 * @param summary 不含敏感信息的有界变更摘要
 */
public record RealtimeOperationEvent(
        UUID eventId,
        UUID organizationId,
        UUID siteId,
        AggregateType aggregateType,
        UUID aggregateId,
        String updateType,
        long businessSequence,
        Instant occurredAt,
        Map<String, Object> summary
) {

    private static final Pattern SUMMARY_KEY = Pattern.compile("[a-z][A-Za-z0-9]{0,63}");
    private static final int MAX_SUMMARY_ENTRIES = 32;
    private static final int MAX_SUMMARY_BYTES = 4096;
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "token", "credential", "certificate", "phone", "idcard", "secret");
    private static final Set<String> UPDATE_TYPES = Set.of(
            "RUNTIME_TRANSITIONED",
            "RECORD_COMPLETED",
            "CANDIDATE_CREATED",
            "CANDIDATE_RESOLVED",
            "CANDIDATE_IGNORED",
            "RECORD_ADDED",
            "RECORD_CORRECTED",
            "RECORD_VOIDED",
            "RECORD_RESTORED");

    /** 校验事件身份、作用域、业务序号和有界摘要。 */
    public RealtimeOperationEvent {
        Objects.requireNonNull(eventId, "运营事件标识不能为空");
        if (eventId.version() != 7) {
            throw new IllegalArgumentException("运营事件标识必须是 UUIDv7");
        }
        Objects.requireNonNull(organizationId, "组织标识不能为空");
        Objects.requireNonNull(siteId, "矿区标识不能为空");
        Objects.requireNonNull(aggregateType, "运营聚合类型不能为空");
        Objects.requireNonNull(aggregateId, "运营聚合标识不能为空");
        if (!UPDATE_TYPES.contains(updateType)) {
            throw new IllegalArgumentException("运营更新类型不合法");
        }
        if (businessSequence < 1) {
            throw new IllegalArgumentException("运营业务序号必须大于 0");
        }
        Objects.requireNonNull(occurredAt, "运营事件发生时间不能为空");
        Objects.requireNonNull(summary, "运营事件摘要不能为空");
        if (summary.size() > MAX_SUMMARY_ENTRIES) {
            throw new IllegalArgumentException("运营事件摘要字段过多");
        }
        Map<String, Object> copied = new LinkedHashMap<>();
        summary.forEach((key, value) -> {
            String normalizedKey = key == null ? "" : key.toLowerCase(java.util.Locale.ROOT);
            if (key == null || !SUMMARY_KEY.matcher(key).matches()
                    || SENSITIVE_KEYS.stream().anyMatch(normalizedKey::contains)) {
                throw new IllegalArgumentException("运营事件摘要字段不合法");
            }
            if (!(value instanceof String || value instanceof Number
                    || value instanceof Boolean || value instanceof UUID)) {
                throw new IllegalArgumentException("运营事件摘要只允许标量值");
            }
            if (value instanceof String text && text.length() > 1024) {
                throw new IllegalArgumentException("运营事件摘要字符串过长");
            }
            copied.put(key, value);
        });
        if (copied.toString().getBytes(StandardCharsets.UTF_8).length > MAX_SUMMARY_BYTES) {
            throw new IllegalArgumentException("运营事件摘要超过大小上限");
        }
        summary = Collections.unmodifiableMap(copied);
    }

    /** 运营实时事件允许暴露的聚合类型。 */
    public enum AggregateType {
        /** 班次实例。 */
        SHIFT_INSTANCE,
        /** 运输作业单。 */
        WORK_ORDER,
        /** 不可变运营执行记录。 */
        OPERATION_RECORD,
        /** 待人工确认运营候选。 */
        OPERATION_CANDIDATE
    }
}
