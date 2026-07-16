package com.bosco.mine.server.modules.device.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 校验 TimescaleDB 设备活动证据批量查询。
 */
class TimescaleDeviceActivityRepositoryTest {

    @Test
    void 应一次查询返回设备首帧和最后接收时间() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ResultSet resultSet = mock(ResultSet.class);
        UUID deviceId = UUID.randomUUID();
        Instant first = Instant.parse("2026-07-16T07:00:00Z");
        Instant last = Instant.parse("2026-07-16T07:59:30Z");
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getObject("device_id", UUID.class)).thenReturn(deviceId);
        when(resultSet.getTimestamp("first_seen_at")).thenReturn(Timestamp.from(first));
        when(resultSet.getTimestamp("last_received_at")).thenReturn(Timestamp.from(last));
        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    ResultSetExtractor<?> extractor = invocation.getArgument(1);
                    return extractor.extractData(resultSet);
                });
        TimescaleDeviceActivityRepository repository =
                new TimescaleDeviceActivityRepository(jdbcTemplate);

        Map<UUID, DeviceActivityRepository.ActivityEvidence> result =
                repository.findByDeviceIds(List.of(deviceId));

        assertThat(result).containsEntry(
                deviceId, new DeviceActivityRepository.ActivityEvidence(first, last)
        );
    }
}
