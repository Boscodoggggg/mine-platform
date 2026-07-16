package com.bosco.mine.server.modules.dashboard.service;

import com.bosco.mine.server.modules.dashboard.repository.OperationsDashboardRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 聚合设备台账与遥测只读数据，为调度员提供具备明确口径的运行首页。
 */
@Service
public class OperationsDashboardService {

    private static final int ATTENTION_LIMIT = 8;

    private final OperationsDashboardRepository repository;
    private final Duration freshness;
    private final ZoneId siteZone;
    private final Clock clock;

    /**
     * 创建生产环境调度首页服务。
     *
     * @param repository       调度首页查询仓库
     * @param freshnessSeconds 数据新鲜度秒数
     * @param siteZoneId       矿区时区
     */
    @Autowired
    public OperationsDashboardService(
            OperationsDashboardRepository repository,
            @Value("${mine.dashboard.freshness-seconds:300}") long freshnessSeconds,
            @Value("${mine.site.zone-id:Asia/Shanghai}") String siteZoneId
    ) {
        this(repository, Duration.ofSeconds(freshnessSeconds), ZoneId.of(siteZoneId), Clock.systemUTC());
    }

    /**
     * 创建可注入时钟的调度首页服务，供确定性测试使用。
     *
     * @param repository 调度首页查询仓库
     * @param freshness  数据新鲜度窗口
     * @param siteZone   矿区时区
     * @param clock      UTC 时钟
     */
    public OperationsDashboardService(
            OperationsDashboardRepository repository,
            Duration freshness,
            ZoneId siteZone,
            Clock clock
    ) {
        if (freshness == null || freshness.isZero() || freshness.isNegative()) {
            throw new IllegalArgumentException("数据新鲜度窗口必须大于零");
        }
        this.repository = repository;
        this.freshness = freshness;
        this.siteZone = siteZone;
        this.clock = clock;
    }

    /**
     * 查询当前调度运行总览。
     *
     * @return 调度运行总览
     */
    public OperationsOverview overview() {
        Instant now = clock.instant();
        List<OperationsDashboardRepository.DashboardDevice> devices = repository.findEnabledDevices();
        try {
            ZonedDateTime siteNow = now.atZone(siteZone);
            Instant todayStart = siteNow.toLocalDate().atStartOfDay(siteZone).toInstant();
            Instant trendStart = siteNow.truncatedTo(ChronoUnit.HOURS).minusHours(23).toInstant();
            OperationsDashboardRepository.TelemetrySnapshot telemetry =
                    repository.readTelemetry(todayStart, trendStart);
            return availableOverview(now, trendStart, devices, telemetry);
        } catch (RuntimeException exception) {
            return unavailableOverview(now, devices.size());
        }
    }

    private OperationsOverview availableOverview(
            Instant now,
            Instant trendStart,
            List<OperationsDashboardRepository.DashboardDevice> devices,
            OperationsDashboardRepository.TelemetrySnapshot telemetry
    ) {
        Instant freshAfter = now.minus(freshness);
        Map<UUID, Instant> lastSeen = telemetry.lastSeenByDevice();
        long reporting = devices.stream()
                .filter(device -> isReporting(lastSeen.get(device.id()), freshAfter))
                .count();
        long stale = devices.stream()
                .filter(device -> isStale(lastSeen.get(device.id()), freshAfter))
                .count();
        long neverReported = devices.size() - reporting - stale;

        List<AttentionDevice> attentionDevices = new ArrayList<>();
        devices.stream()
                .filter(device -> lastSeen.get(device.id()) == null)
                .map(device -> attention(device, "NEVER_REPORTED", null))
                .forEach(attentionDevices::add);
        devices.stream()
                .filter(device -> isStale(lastSeen.get(device.id()), freshAfter))
                .sorted(Comparator.comparing(device -> lastSeen.get(device.id())))
                .map(device -> attention(device, "STALE", lastSeen.get(device.id())))
                .forEach(attentionDevices::add);

        return new OperationsOverview(
                now,
                "AVAILABLE",
                Math.toIntExact(freshness.toSeconds()),
                new DeviceSummary(devices.size(), reporting, stale, neverReported),
                new TelemetrySummary(
                        telemetry.todayMessages(),
                        telemetry.latestReceivedAt(),
                        completeHourlyBuckets(trendStart, telemetry.hourly())
                ),
                attentionDevices.stream().limit(ATTENTION_LIMIT).toList()
        );
    }

    private List<HourBucket> completeHourlyBuckets(
            Instant trendStart,
            List<OperationsDashboardRepository.HourCount> source
    ) {
        Map<Instant, Long> counts = source.stream().collect(java.util.stream.Collectors.toMap(
                OperationsDashboardRepository.HourCount::hour,
                OperationsDashboardRepository.HourCount::count,
                Long::sum
        ));
        return java.util.stream.LongStream.range(0, 24)
                .mapToObj(offset -> {
                    Instant hour = trendStart.plus(offset, ChronoUnit.HOURS);
                    return new HourBucket(hour, counts.getOrDefault(hour, 0L));
                })
                .toList();
    }

    private OperationsOverview unavailableOverview(Instant now, int enabledDevices) {
        return new OperationsOverview(
                now,
                "UNAVAILABLE",
                Math.toIntExact(freshness.toSeconds()),
                new DeviceSummary(enabledDevices, null, null, null),
                new TelemetrySummary(null, null, List.of()),
                List.of()
        );
    }

    private boolean isReporting(Instant lastSeen, Instant freshAfter) {
        return lastSeen != null && !lastSeen.isBefore(freshAfter);
    }

    private boolean isStale(Instant lastSeen, Instant freshAfter) {
        return lastSeen != null && lastSeen.isBefore(freshAfter);
    }

    private AttentionDevice attention(
            OperationsDashboardRepository.DashboardDevice device,
            String state,
            Instant lastSeenAt
    ) {
        return new AttentionDevice(device.id(), device.deviceCode(), device.name(), state, lastSeenAt);
    }

    /**
     * 调度运行总览响应。
     *
     * @param generatedAt      生成时间
     * @param dataStatus       遥测数据可用状态
     * @param freshnessSeconds 数据新鲜度秒数
     * @param devices          设备统计
     * @param telemetry        遥测统计
     * @param attentionDevices 需要关注的设备
     */
    public record OperationsOverview(
            Instant generatedAt,
            String dataStatus,
            int freshnessSeconds,
            DeviceSummary devices,
            TelemetrySummary telemetry,
            List<AttentionDevice> attentionDevices
    ) {
    }

    /**
     * 设备运行统计。
     *
     * @param enabled       启用设备数
     * @param reporting     正常上报设备数，不可用时为空
     * @param stale         数据中断设备数，不可用时为空
     * @param neverReported 从未上报设备数，不可用时为空
     */
    public record DeviceSummary(long enabled, Long reporting, Long stale, Long neverReported) {
    }

    /**
     * 遥测消息统计。
     *
     * @param todayMessages    今日消息数，不可用时为空
     * @param latestReceivedAt 最近消息接收时间
     * @param hourly           最近二十四小时消息桶
     */
    public record TelemetrySummary(Long todayMessages, Instant latestReceivedAt, List<HourBucket> hourly) {
    }

    /**
     * 小时消息桶。
     *
     * @param hour  小时起点
     * @param count 消息数量
     */
    public record HourBucket(Instant hour, long count) {
    }

    /**
     * 需要调度员关注的设备。
     *
     * @param deviceId   设备标识
     * @param deviceCode 设备编码
     * @param name       设备名称
     * @param state      关注状态
     * @param lastSeenAt 最后上报时间
     */
    public record AttentionDevice(
            UUID deviceId,
            String deviceCode,
            String name,
            String state,
            Instant lastSeenAt
    ) {
    }
}
