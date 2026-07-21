package com.bosco.mine.server.modules.device.service;

import com.bosco.mine.common.error.MineException;
import com.bosco.mine.model.message.UnifiedDeviceMessage.Quality;
import com.bosco.mine.server.modules.device.dto.DeviceLatestResponse;
import com.bosco.mine.server.modules.device.dto.TelemetryHistoryResponse;
import com.bosco.mine.server.modules.device.model.Device;
import com.bosco.mine.server.modules.device.repository.DeviceMapper;
import com.bosco.mine.server.modules.device.repository.DeviceTelemetryRepository;
import com.bosco.mine.server.modules.device.repository.DeviceTelemetryRepository.Aggregation;
import com.bosco.mine.server.modules.device.repository.DeviceTelemetryRepository.HistoryPage;
import com.bosco.mine.server.modules.device.repository.DeviceTelemetryRepository.HistoryPoint;
import com.bosco.mine.server.modules.device.repository.DeviceTelemetryRepository.HistorySource;
import com.bosco.mine.server.modules.device.repository.DeviceTelemetryRepository.HistoryWatermarks;
import com.bosco.mine.server.modules.device.repository.DeviceTelemetryRepository.TelemetryValue;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验设备遥测查询边界、缓存回源和并发合并规则。
 */
class DeviceTelemetryQueryServiceTest {

    @Test
    void 最新值应优先读取Redis并仅回源缺失属性() {
        UUID deviceId = UUID.randomUUID();
        DeviceMapper mapper = mapperWithDevice(deviceId);
        DeviceTelemetryRepository repository = mock(DeviceTelemetryRepository.class);
        TelemetryValue speed = value("speed", 12.5D, Quality.GOOD);
        TelemetryValue rpm = value("rpm", 800D, Quality.UNCERTAIN);
        when(repository.findCachedLatest(deviceId, "speed")).thenReturn(Optional.of(speed));
        when(repository.findCachedLatest(deviceId, "rpm"))
                .thenReturn(Optional.empty(), Optional.of(rpm));
        when(repository.findLatestHistory(deviceId, List.of("rpm")))
                .thenReturn(Map.of("rpm", rpm));
        when(repository.cacheLatestIfAbsent(deviceId, rpm)).thenReturn(true);
        DeviceTelemetryQueryService service = new DeviceTelemetryQueryService(mapper, repository);

        DeviceLatestResponse response = service.findLatest(deviceId, List.of("speed", "rpm"));

        assertThat(response.items())
                .extracting(item -> item.propertyId() + ":" + item.source())
                .containsExactly("speed:REDIS", "rpm:TIMESCALEDB");
        assertThat(response.items()).extracting(item -> item.quality())
                .containsExactly(Quality.GOOD, Quality.UNCERTAIN);
        assertThat(response.missingPropertyIds()).isEmpty();
        verify(repository).cacheLatestIfAbsent(deviceId, rpm);
        verify(repository, times(1)).findLatestHistory(deviceId, List.of("rpm"));
    }

    @Test
    void 同一设备属性并发未命中时只允许一次Timescale回源() throws Exception {
        UUID deviceId = UUID.randomUUID();
        DeviceMapper mapper = mapperWithDevice(deviceId);
        DeviceTelemetryRepository repository = mock(DeviceTelemetryRepository.class);
        TelemetryValue speed = value("speed", 9.5D, Quality.GOOD);
        CountDownLatch queryStarted = new CountDownLatch(1);
        CountDownLatch allowQueryReturn = new CountDownLatch(1);
        when(repository.findCachedLatest(deviceId, "speed"))
                .thenReturn(Optional.empty(), Optional.empty(), Optional.of(speed));
        when(repository.findLatestHistory(deviceId, List.of("speed"))).thenAnswer(invocation -> {
            queryStarted.countDown();
            if (!allowQueryReturn.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("测试未及时放行回源查询");
            }
            return Map.of("speed", speed);
        });
        when(repository.cacheLatestIfAbsent(deviceId, speed)).thenReturn(true);
        DeviceTelemetryQueryService service = new DeviceTelemetryQueryService(mapper, repository);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<DeviceLatestResponse> first = executor.submit(
                    () -> service.findLatest(deviceId, List.of("speed"))
            );
            assertThat(queryStarted.await(1, TimeUnit.SECONDS)).isTrue();
            Future<DeviceLatestResponse> second = executor.submit(
                    () -> service.findLatest(deviceId, List.of("speed"))
            );
            verify(repository, org.mockito.Mockito.timeout(1000).times(2))
                    .findCachedLatest(deviceId, "speed");
            allowQueryReturn.countDown();

            assertThat(first.get(2, TimeUnit.SECONDS).items()).hasSize(1);
            assertThat(second.get(2, TimeUnit.SECONDS).items()).hasSize(1);
        }
        verify(repository, times(1)).findLatestHistory(deviceId, List.of("speed"));
        verify(repository, times(1)).cacheLatestIfAbsent(deviceId, speed);
    }

    @Test
    void 回填竞争失败时必须返回Redis中的采集新值() {
        UUID deviceId = UUID.randomUUID();
        DeviceTelemetryRepository repository = mock(DeviceTelemetryRepository.class);
        TelemetryValue history = value("speed", 10D, Quality.GOOD);
        TelemetryValue realtime = value("speed", 20D, Quality.GOOD);
        when(repository.findCachedLatest(deviceId, "speed"))
                .thenReturn(Optional.empty(), Optional.of(realtime));
        when(repository.findLatestHistory(deviceId, List.of("speed")))
                .thenReturn(Map.of("speed", history));
        when(repository.cacheLatestIfAbsent(deviceId, history)).thenReturn(false);
        DeviceTelemetryQueryService service = new DeviceTelemetryQueryService(
                mapperWithDevice(deviceId), repository
        );

        DeviceLatestResponse response = service.findLatest(deviceId, List.of("speed"));

        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.value()).isEqualTo(20D);
            assertThat(item.source()).isEqualTo("REDIS");
        });
    }

    @Test
    void 一百个冷属性必须合并为一次Timescale查询() {
        UUID deviceId = UUID.randomUUID();
        DeviceTelemetryRepository repository = mock(DeviceTelemetryRepository.class);
        List<String> properties = java.util.stream.IntStream.range(0, 100)
                .mapToObj(index -> "p" + index)
                .toList();
        when(repository.findLatestHistory(deviceId, properties)).thenReturn(Map.of());
        DeviceTelemetryQueryService service = new DeviceTelemetryQueryService(
                mapperWithDevice(deviceId), repository
        );

        DeviceLatestResponse response = service.findLatest(deviceId, properties);

        assertThat(response.items()).isEmpty();
        assertThat(response.missingPropertyIds()).containsExactlyElementsOf(properties);
        verify(repository, times(1)).findLatestHistory(deviceId, properties);
    }

    @Test
    void 回源查询发起者超时也必须收到五零三错误() {
        UUID deviceId = UUID.randomUUID();
        DeviceTelemetryRepository repository = mock(DeviceTelemetryRepository.class);
        when(repository.findLatestHistory(deviceId, List.of("speed")))
                .thenThrow(new QueryTimeoutException("query timeout"));
        DeviceTelemetryQueryService service = new DeviceTelemetryQueryService(
                mapperWithDevice(deviceId), repository
        );

        assertThatThrownBy(() -> service.findLatest(deviceId, List.of("speed")))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code())
                                .isEqualTo("TELEMETRY-503-001"));
    }

    @Test
    void 时序库连接资源不可用也必须收到五零三错误() {
        UUID deviceId = UUID.randomUUID();
        DeviceTelemetryRepository repository = mock(DeviceTelemetryRepository.class);
        when(repository.findLatestHistory(deviceId, List.of("speed")))
                .thenThrow(new DataAccessResourceFailureException("connection timeout"));
        DeviceTelemetryQueryService service = new DeviceTelemetryQueryService(
                mapperWithDevice(deviceId), repository
        );

        assertThatThrownBy(() -> service.findLatest(deviceId, List.of("speed")))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code())
                                .isEqualTo("TELEMETRY-503-001"));
    }

    @Test
    void Redis读取不可用时应降级到持久最新值() {
        UUID deviceId = UUID.randomUUID();
        DeviceTelemetryRepository repository = mock(DeviceTelemetryRepository.class);
        TelemetryValue speed = value("speed", 12.5D, Quality.GOOD);
        when(repository.findCachedLatest(deviceId, "speed"))
                .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException(
                        "redis unavailable"
                ));
        when(repository.findLatestHistory(deviceId, List.of("speed")))
                .thenReturn(Map.of("speed", speed));
        DeviceTelemetryQueryService service = new DeviceTelemetryQueryService(
                mapperWithDevice(deviceId), repository
        );

        DeviceLatestResponse response = service.findLatest(deviceId, List.of("speed"));

        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.value()).isEqualTo(12.5D);
            assertThat(item.source()).isEqualTo("TIMESCALEDB");
        });
    }

    @Test
    void Redis回填期间不可用时仍应返回持久最新值() {
        UUID deviceId = UUID.randomUUID();
        DeviceTelemetryRepository repository = mock(DeviceTelemetryRepository.class);
        TelemetryValue speed = value("speed", 12.5D, Quality.GOOD);
        when(repository.findLatestHistory(deviceId, List.of("speed")))
                .thenReturn(Map.of("speed", speed));
        org.mockito.Mockito.doThrow(
                new org.springframework.data.redis.RedisConnectionFailureException(
                        "redis unavailable"
                )
        ).when(repository).restoreLatestState(deviceId);
        DeviceTelemetryQueryService service = new DeviceTelemetryQueryService(
                mapperWithDevice(deviceId), repository
        );

        DeviceLatestResponse response = service.findLatest(deviceId, List.of("speed"));

        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.value()).isEqualTo(12.5D);
            assertThat(item.source()).isEqualTo("TIMESCALEDB");
        });
    }

    @Test
    void 原始历史查询不得超过七天() {
        UUID deviceId = UUID.randomUUID();
        DeviceTelemetryQueryService service = new DeviceTelemetryQueryService(
                mapperWithDevice(deviceId), mock(DeviceTelemetryRepository.class)
        );
        Instant from = Instant.parse("2026-07-01T00:00:00Z");

        assertThatThrownBy(() -> service.findHistory(
                deviceId, "speed", from, from.plus(Duration.ofDays(8)),
                "RAW", null, null, null, 1, 100
        )).isInstanceOfSatisfying(MineException.class, exception ->
                assertThat(exception.errorCode().code()).isEqualTo("TELEMETRY-400-001"));
    }

    @Test
    void 聚合历史查询必须携带合法时间桶并返回分页质量() {
        UUID deviceId = UUID.randomUUID();
        DeviceTelemetryRepository repository = mock(DeviceTelemetryRepository.class);
        DeviceTelemetryQueryService service = new DeviceTelemetryQueryService(
                mapperWithDevice(deviceId), repository
        );
        Instant from = Instant.parse("2026-07-15T00:00:00Z");
        Instant to = from.plus(Duration.ofHours(1));

        assertThatThrownBy(() -> service.findHistory(
                deviceId, "speed", from, to, "AVG", null, null, null, 1, 100
        )).isInstanceOfSatisfying(MineException.class, exception ->
                assertThat(exception.errorCode().code()).isEqualTo("TELEMETRY-400-002"));
        assertThatThrownBy(() -> service.findHistory(
                deviceId, "speed", from, to, "AVG",
                Duration.ofSeconds(60).plusMillis(500), null, null, 1, 100
        )).isInstanceOfSatisfying(MineException.class, exception ->
                assertThat(exception.errorCode().code()).isEqualTo("TELEMETRY-400-002"));

        HistoryPoint point = new HistoryPoint(
                from, to, 10.5D, Quality.BAD, null, 20L, HistorySource.ROLLUP
        );
        when(repository.findHistory(any())).thenReturn(new HistoryPage(List.of(point), 1));
        when(repository.currentHistoryWatermarks()).thenReturn(new HistoryWatermarks(42L, 9L));
        TelemetryHistoryResponse response = service.findHistory(
                deviceId, "speed", from, to, "AVG", Duration.ofMinutes(5),
                42L, 9L, 2, 10
        );

        assertThat(response.page()).isEqualTo(2);
        assertThat(response.size()).isEqualTo(10);
        assertThat(response.total()).isOne();
        assertThat(response.watermark()).isEqualTo(42L);
        assertThat(response.rollupWatermark()).isEqualTo(9L);
        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.quality()).isEqualTo(Quality.BAD);
            assertThat(item.sampleCount()).isEqualTo(20);
            assertThat(item.source()).isEqualTo("ROLLUP");
        });
        verify(repository).findHistory(org.mockito.ArgumentMatchers.argThat(query ->
                query.aggregation() == Aggregation.AVG
                        && query.offset() == 10
                        && query.limit() == 10
                        && query.watermark() == 42L
                        && query.rollupWatermark() == 9L
                        && Duration.ofMinutes(5).equals(query.bucket())
        ));
    }

    @Test
    void 后续页必须原样携带两个历史水位且不得使用未来聚合水位() {
        UUID deviceId = UUID.randomUUID();
        DeviceTelemetryRepository repository = mock(DeviceTelemetryRepository.class);
        when(repository.currentHistoryWatermarks()).thenReturn(new HistoryWatermarks(42L, 9L));
        DeviceTelemetryQueryService service = new DeviceTelemetryQueryService(
                mapperWithDevice(deviceId), repository
        );
        Instant from = Instant.parse("2026-07-15T00:00:00Z");
        Instant to = from.plusSeconds(60);

        assertThatThrownBy(() -> service.findHistory(
                deviceId, "speed", from, to, "AVG", Duration.ofMinutes(1),
                42L, null, 2, 100
        )).isInstanceOfSatisfying(MineException.class, exception ->
                assertThat(exception.errorCode().code()).isEqualTo("TELEMETRY-400-001"));
        assertThatThrownBy(() -> service.findHistory(
                deviceId, "speed", from, to, "AVG", Duration.ofMinutes(1),
                42L, 10L, 2, 100
        )).isInstanceOfSatisfying(MineException.class, exception ->
                assertThat(exception.errorCode().code()).isEqualTo("TELEMETRY-400-001"));
        verify(repository, org.mockito.Mockito.never()).findHistory(any());
    }

    @Test
    void 历史查询连接不可用时也必须收到五零三错误() {
        UUID deviceId = UUID.randomUUID();
        DeviceTelemetryRepository repository = mock(DeviceTelemetryRepository.class);
        when(repository.findHistory(any()))
                .thenThrow(new DataAccessResourceFailureException("connection timeout"));
        when(repository.currentHistoryWatermarks()).thenReturn(new HistoryWatermarks(42L, 9L));
        DeviceTelemetryQueryService service = new DeviceTelemetryQueryService(
                mapperWithDevice(deviceId), repository
        );
        Instant from = Instant.parse("2026-07-15T00:00:00Z");

        assertThatThrownBy(() -> service.findHistory(
                deviceId, "speed", from, from.plusSeconds(60),
                "RAW", null, 42L, 9L, 1, 100
        )).isInstanceOfSatisfying(MineException.class, exception ->
                assertThat(exception.errorCode().code()).isEqualTo("TELEMETRY-503-001"));
    }

    @Test
    void 客户端不得使用超过当前已提交值的未来水位() {
        UUID deviceId = UUID.randomUUID();
        DeviceTelemetryRepository repository = mock(DeviceTelemetryRepository.class);
        when(repository.currentHistoryWatermarks()).thenReturn(new HistoryWatermarks(42L, 9L));
        DeviceTelemetryQueryService service = new DeviceTelemetryQueryService(
                mapperWithDevice(deviceId), repository
        );
        Instant from = Instant.parse("2026-07-15T00:00:00Z");

        assertThatThrownBy(() -> service.findHistory(
                deviceId, "speed", from, from.plusSeconds(60),
                "RAW", null, 43L, 9L, 1, 100
        )).isInstanceOfSatisfying(MineException.class, exception ->
                assertThat(exception.errorCode().code()).isEqualTo("TELEMETRY-400-001"));
        verify(repository, org.mockito.Mockito.never()).findHistory(any());
    }

    @Test
    void 查询不存在设备应在访问时序库前返回四零四() {
        UUID deviceId = UUID.randomUUID();
        DeviceMapper mapper = mock(DeviceMapper.class);
        DeviceTelemetryRepository repository = mock(DeviceTelemetryRepository.class);
        when(mapper.findDeviceById(deviceId)).thenReturn(Optional.empty());
        DeviceTelemetryQueryService service = new DeviceTelemetryQueryService(mapper, repository);

        assertThatThrownBy(() -> service.findLatest(deviceId, List.of("speed")))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("DEVICE-404-002"));
        verify(repository, times(0)).findCachedLatest(any(), any());
    }

    private DeviceMapper mapperWithDevice(UUID deviceId) {
        DeviceMapper mapper = mock(DeviceMapper.class);
        when(mapper.findDeviceById(deviceId)).thenReturn(Optional.of(new Device(
                deviceId,
                UUID.randomUUID(),
                "truck-001",
                "一号矿卡",
                "ENABLED",
                null,
                Instant.parse("2026-07-15T00:00:00Z"),
                Instant.parse("2026-07-15T00:00:00Z"),
                0
        )));
        return mapper;
    }

    private TelemetryValue value(String propertyId, double numericValue, Quality quality) {
        return new TelemetryValue(
                propertyId,
                numericValue,
                Instant.parse("2026-07-15T01:00:00Z"),
                Instant.parse("2026-07-15T01:00:00Z"),
                Instant.parse("2026-07-15T01:00:01Z"),
                quality,
                UUID.randomUUID(),
                "boot-a",
                10L
        );
    }

}
