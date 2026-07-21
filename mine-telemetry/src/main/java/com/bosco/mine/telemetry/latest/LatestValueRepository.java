package com.bosco.mine.telemetry.latest;

import com.bosco.mine.model.message.UnifiedDeviceMessage.Quality;
import com.bosco.mine.telemetry.pipeline.TelemetrySample;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 使用 Redis 保存设备实时最新值，并通过 Lua 在服务并发和消息重投场景下保证比较与更新原子化。
 */
public final class LatestValueRepository {

    private static final String VALUE_KEY_PREFIX = "mine:latest:";
    private static final String ORDER_KEY_PREFIX = "mine:latest-order:";
    private static final String STATE_KEY_PREFIX = "mine:latest-state:";
    private static final String RETIRED_BOOT_KEY_PREFIX = "mine:latest-retired-boots:";
    private static final DefaultRedisScript<Long> UPDATE_IF_NEWER = new DefaultRedisScript<>("""
            local bootId = ARGV[1]
            local sequence = tonumber(ARGV[2])
            local incomingTime = tonumber(ARGV[3])
            local eventId = ARGV[4]
            local propertyCount = tonumber(ARGV[5])
            local currentBoot = redis.call('HGET', KEYS[3], 'currentBootId')
            local highestSequence = tonumber(redis.call('HGET', KEYS[3], 'highestSequence') or '-1')
            local latestTime = tonumber(redis.call('HGET', KEYS[3], 'latestEffectiveAt'))
            local latestEventId = redis.call('HGET', KEYS[3], 'latestEventId') or ''
            local reliableOrder = bootId ~= '' and sequence >= 0
            local firstReliableBoot = reliableOrder and (not currentBoot or currentBoot == '')
            local newBoot = false
            local sameBoot = false

            if reliableOrder then
                if redis.call('SISMEMBER', KEYS[4], bootId) == 1 then
                    return 0
                end
                if currentBoot and currentBoot ~= '' then
                    if bootId == currentBoot then
                        if sequence <= highestSequence then
                            return 0
                        end
                        sameBoot = true
                    else
                        newBoot = true
                    end
                end
            end

            local updateLatest = false
            if not latestTime or incomingTime > latestTime then
                updateLatest = true
            elseif incomingTime == latestTime then
                if reliableOrder and (firstReliableBoot or newBoot
                        or (bootId == currentBoot and sequence > highestSequence)) then
                    updateLatest = true
                elseif not reliableOrder and eventId > latestEventId then
                    updateLatest = true
                end
            end
            if reliableOrder and (sameBoot or firstReliableBoot) then
                redis.call('HSET', KEYS[3],
                        'currentBootId', bootId,
                        'highestSequence', sequence)
            end
            if not updateLatest then
                return 0
            end

            if reliableOrder then
                if newBoot then
                    redis.call('SADD', KEYS[4], currentBoot)
                end
                redis.call('HSET', KEYS[3],
                        'currentBootId', bootId,
                        'highestSequence', sequence)
            end
            redis.call('HSET', KEYS[3],
                    'latestEffectiveAt', incomingTime,
                    'latestEventId', eventId)
            local argumentIndex = 6
            for _ = 1, propertyCount do
                local propertyId = ARGV[argumentIndex]
                local valueJson = ARGV[argumentIndex + 1]
                redis.call('HSET', KEYS[1], propertyId, valueJson)
                redis.call('HSET', KEYS[2], propertyId,
                        incomingTime .. '|' .. bootId .. '|' .. sequence .. '|' .. eventId)
                argumentIndex = argumentIndex + 2
            end
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;

    /**
     * 创建实时最新值仓储。
     *
     * @param redisTemplate Redis 字符串操作模板
     */
    public LatestValueRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "Redis 操作模板不能为空");
        this.jsonMapper = JsonMapper.builder().findAndAddModules().build();
    }

    /**
     * 当遥测点比当前值更新时原子替换最新值。
     *
     * @param sample 待比较的标准遥测点
     * @return 实际完成更新时返回 {@code true}，迟到或重复数据返回 {@code false}
     */
    public boolean updateIfNewer(TelemetrySample sample) {
        Objects.requireNonNull(sample, "遥测点不能为空");
        return updateEventIfNewer(List.of(sample));
    }

    /**
     * 原子判定一帧设备顺序，并在允许更新时一次写入该帧的全部数值属性。
     *
     * <p>同一启动周期的小序号、重复序号和已退休启动周期均不得覆盖最新值；设备重启后序号可从头开始。</p>
     *
     * @param samples 同一事件展开出的全部数值遥测点
     * @return 整帧实际更新最新值时返回 {@code true}
     */
    public boolean updateEventIfNewer(List<TelemetrySample> samples) {
        Objects.requireNonNull(samples, "遥测点集合不能为空");
        if (samples.isEmpty()) {
            throw new IllegalArgumentException("遥测点集合不能为空");
        }
        TelemetrySample first = samples.getFirst();
        List<String> arguments = new ArrayList<>(5 + samples.size() * 2);
        arguments.add(first.bootId() == null ? "" : first.bootId());
        arguments.add(Long.toString(first.sequence() == null ? -1L : first.sequence()));
        arguments.add(Long.toString(first.effectiveAt().toEpochMilli()));
        arguments.add(first.eventId().toString());
        arguments.add(Integer.toString(samples.size()));
        for (TelemetrySample sample : samples) {
            validateSameEvent(first, sample);
            arguments.add(sample.propertyId());
            arguments.add(write(new LatestValue(
                    sample.value(),
                    sample.occurredAt(),
                    sample.receivedAt(),
                    sample.quality(),
                    sample.eventId(),
                    sample.bootId(),
                    sample.sequence()
            )));
        }
        Object[] scriptArguments = arguments.toArray();
        Long result = redisTemplate.execute(
                UPDATE_IF_NEWER,
                List.of(
                        valueKey(first.deviceId()),
                        orderKey(first.deviceId()),
                        stateKey(first.deviceId()),
                        retiredBootKey(first.deviceId())
                ),
                scriptArguments
        );
        return Long.valueOf(1L).equals(result);
    }

    /**
     * 查询设备指定属性的实时最新值。
     *
     * @param deviceId  平台内部设备标识
     * @param propertyId 物模型属性标识
     * @return 存在时返回最新值
     */
    public Optional<LatestValue> find(UUID deviceId, String propertyId) {
        Objects.requireNonNull(deviceId, "设备标识不能为空");
        Objects.requireNonNull(propertyId, "属性标识不能为空");
        Object value = redisTemplate.opsForHash().get(valueKey(deviceId), propertyId);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(jsonMapper.readValue(value.toString(), LatestValue.class));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Redis 最新值反序列化失败", exception);
        }
    }

    private String write(LatestValue latestValue) {
        try {
            return jsonMapper.writeValueAsString(latestValue);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Redis 最新值序列化失败", exception);
        }
    }

    private void validateSameEvent(TelemetrySample first, TelemetrySample candidate) {
        if (!first.eventId().equals(candidate.eventId())
                || !first.deviceId().equals(candidate.deviceId())
                || !Objects.equals(first.bootId(), candidate.bootId())
                || !Objects.equals(first.sequence(), candidate.sequence())
                || !first.effectiveAt().equals(candidate.effectiveAt())) {
            throw new IllegalArgumentException("一次 Redis 原子更新只能包含同一设备事件的遥测点");
        }
    }

    private String valueKey(UUID deviceId) {
        return VALUE_KEY_PREFIX + deviceId;
    }

    private String orderKey(UUID deviceId) {
        return ORDER_KEY_PREFIX + deviceId;
    }

    private String stateKey(UUID deviceId) {
        return STATE_KEY_PREFIX + deviceId;
    }

    private String retiredBootKey(UUID deviceId) {
        return RETIRED_BOOT_KEY_PREFIX + deviceId;
    }

    /**
     * Redis 中可直接提供给实时监控接口的最新值快照。
     *
     * @param value      数值型属性值
     * @param occurredAt 设备声明的发生时间
     * @param receivedAt 平台接收时间
     * @param quality    数据质量
     * @param eventId    产生该值的事件标识
     * @param bootId     设备启动周期标识
     * @param sequence   启动周期内序号
     */
    public record LatestValue(
            double value,
            Instant occurredAt,
            Instant receivedAt,
            Quality quality,
            UUID eventId,
            String bootId,
            Long sequence) {

        /**
         * 创建并校验最新值快照。
         */
        public LatestValue {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("最新遥测值必须是有限数值");
            }
            occurredAt = Objects.requireNonNull(occurredAt, "设备发生时间不能为空");
            receivedAt = Objects.requireNonNull(receivedAt, "平台接收时间不能为空");
            quality = Objects.requireNonNull(quality, "数据质量不能为空");
            eventId = Objects.requireNonNull(eventId, "事件标识不能为空");
        }
    }
}
