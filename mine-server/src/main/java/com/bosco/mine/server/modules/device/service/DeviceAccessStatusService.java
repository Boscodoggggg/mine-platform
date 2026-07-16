package com.bosco.mine.server.modules.device.service;

import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.modules.device.dto.DeviceAccessStatusResponse;
import com.bosco.mine.server.modules.device.dto.DeviceAccessTestResponse;
import com.bosco.mine.server.modules.device.dto.DeviceConnectionTestResponse;
import com.bosco.mine.server.modules.device.infrastructure.DeviceTransportSessionProbe;
import com.bosco.mine.server.modules.device.model.DeviceConnectionProfile;
import com.bosco.mine.server.modules.device.repository.DeviceActivityRepository;
import com.bosco.mine.server.modules.device.repository.DeviceConnectionProfileMapper;
import com.bosco.mine.server.modules.device.repository.DeviceMapper;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 聚合端点、传输会话、有效首帧和数据落库四层设备接入状态。
 */
@Service
public class DeviceAccessStatusService {

    private static final ErrorCode DEVICE_NOT_FOUND = new ErrorCode("DEVICE-404-002", "设备不存在");

    private final DeviceMapper deviceMapper;
    private final DeviceConnectionProfileMapper profileMapper;
    private final Optional<DeviceActivityRepository> activityRepository;
    private final DeviceTransportSessionProbe transportProbe;
    private final DeviceConnectionService connectionService;
    private final OperationAuditService auditService;
    private final Clock clock;

    /**
     * 创建设备接入状态服务。
     *
     * @param deviceMapper 设备数据接口
     * @param profileMapper 接入配置数据接口
     * @param activityRepository 可选遥测活动证据接口
     * @param transportProbe 传输会话探针
     * @param connectionService 端点诊断服务
     * @param auditService 操作审计服务
     */
    @Autowired
    public DeviceAccessStatusService(
            DeviceMapper deviceMapper,
            DeviceConnectionProfileMapper profileMapper,
            Optional<DeviceActivityRepository> activityRepository,
            DeviceTransportSessionProbe transportProbe,
            DeviceConnectionService connectionService,
            OperationAuditService auditService
    ) {
        this(
                deviceMapper, profileMapper, activityRepository, transportProbe,
                connectionService, auditService, Clock.systemUTC()
        );
    }

    /**
     * 创建可注入时钟的设备接入状态服务。
     *
     * @param deviceMapper 设备数据接口
     * @param profileMapper 接入配置数据接口
     * @param activityRepository 可选遥测活动证据接口
     * @param transportProbe 传输会话探针
     * @param connectionService 端点诊断服务
     * @param auditService 操作审计服务
     * @param clock 平台时钟
     */
    DeviceAccessStatusService(
            DeviceMapper deviceMapper,
            DeviceConnectionProfileMapper profileMapper,
            Optional<DeviceActivityRepository> activityRepository,
            DeviceTransportSessionProbe transportProbe,
            DeviceConnectionService connectionService,
            OperationAuditService auditService,
            Clock clock
    ) {
        this.deviceMapper = deviceMapper;
        this.profileMapper = profileMapper;
        this.activityRepository = activityRepository;
        this.transportProbe = transportProbe;
        this.connectionService = connectionService;
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * 批量查询设备持续接入状态，不访问 RabbitMQ 管理面。
     *
     * @param deviceIds 设备标识集合
     * @return 与请求顺序一致的状态列表
     */
    public List<DeviceAccessStatusResponse> find(Collection<UUID> deviceIds) {
        List<UUID> ids = List.copyOf(deviceIds);
        ActivityQueryResult activity = queryActivity(ids);
        List<DeviceAccessStatusResponse> result = new ArrayList<>(ids.size());
        for (UUID deviceId : ids) {
            requireDevice(deviceId);
            Optional<DeviceConnectionProfile> profile = profileMapper.findByDeviceId(deviceId);
            result.add(status(
                    deviceId, profile.orElse(null), activity.evidence().get(deviceId), activity.sourceAvailable()
            ));
        }
        return List.copyOf(result);
    }

    /**
     * 手动执行一次四层接入状态检查。
     *
     * @param deviceId 设备标识
     * @return 四层检查结果
     */
    public DeviceAccessTestResponse test(UUID deviceId) {
        requireDevice(deviceId);
        DeviceConnectionProfile profile = profileMapper.findByDeviceId(deviceId).orElse(null);
        Instant startedAt = clock.instant();
        if (profile == null) {
            List<DeviceAccessTestResponse.Stage> stages = List.of(
                    new DeviceAccessTestResponse.Stage(
                            "ENDPOINT", "WAITING", "ACCESS_NOT_CONFIGURED", null, 0
                    ),
                    new DeviceAccessTestResponse.Stage(
                            "TRANSPORT", "SKIPPED", "DEPENDENCY_SKIPPED", null, 0
                    ),
                    new DeviceAccessTestResponse.Stage(
                            "ACTIVITY", "SKIPPED", "DEPENDENCY_SKIPPED", null, 0
                    ),
                    new DeviceAccessTestResponse.Stage(
                            "DATA", "SKIPPED", "DEPENDENCY_SKIPPED", null, 0
                    )
            );
            auditService.recordSuccess(
                    "DEVICE_MANAGEMENT", "TEST_DEVICE_ACCESS_STATUS", "device:connection:test",
                    "DEVICE_ACCESS_STATUS", deviceId, Map.of(), Map.of("status", "NOT_CONFIGURED")
            );
            return new DeviceAccessTestResponse(
                    deviceId, "NOT_CONFIGURED", startedAt, 0, "ACCESS_NOT_CONFIGURED", stages
            );
        }
        DeviceConnectionTestResponse endpoint = connectionService.test(deviceId);
        DeviceTransportSessionProbe.Result transport = transportProbe.probe(deviceId, profile.connectionMode());
        ActivityQueryResult activity = queryActivity(List.of(deviceId));
        DeviceActivityRepository.ActivityEvidence evidence = activity.evidence().get(deviceId);
        List<DeviceAccessTestResponse.Stage> stages = stages(
                endpoint, transport, evidence, profile, activity.sourceAvailable()
        );
        String overall = overall(endpoint, transport, evidence, profile, activity.sourceAvailable());
        long duration = Math.max(0, Duration.between(startedAt, clock.instant()).toMillis());
        auditService.recordSuccess(
                "DEVICE_MANAGEMENT", "TEST_DEVICE_ACCESS_STATUS", "device:connection:test",
                "DEVICE_ACCESS_STATUS", deviceId, Map.of(), Map.of("status", overall)
        );
        return new DeviceAccessTestResponse(
                deviceId, overall, startedAt, duration, message(overall, transport, evidence), stages
        );
    }

    private List<DeviceAccessTestResponse.Stage> stages(
            DeviceConnectionTestResponse endpoint,
            DeviceTransportSessionProbe.Result transport,
            DeviceActivityRepository.ActivityEvidence evidence,
            DeviceConnectionProfile profile,
            boolean activitySourceAvailable
    ) {
        String endpointStatus = "FAILED".equals(endpoint.status()) ? "FAILED" : "PASSED";
        List<DeviceAccessTestResponse.Stage> stages = new ArrayList<>();
        stages.add(new DeviceAccessTestResponse.Stage(
                "ENDPOINT", endpointStatus, endpoint.message(), endpoint.startedAt(), endpoint.durationMillis()
        ));
        stages.add(new DeviceAccessTestResponse.Stage(
                "TRANSPORT", transport.status(), transport.message(), transport.observedAt(), 0
        ));
        if (!activitySourceAvailable) {
            stages.add(new DeviceAccessTestResponse.Stage(
                    "ACTIVITY", "UNKNOWN", "ACTIVITY_SOURCE_UNAVAILABLE", null, 0
            ));
            stages.add(new DeviceAccessTestResponse.Stage(
                    "DATA", "UNKNOWN", "ACTIVITY_SOURCE_UNAVAILABLE", null, 0
            ));
        } else if (evidence == null) {
            stages.add(new DeviceAccessTestResponse.Stage(
                    "ACTIVITY", "WAITING", "FIRST_FRAME_WAITING", null, 0
            ));
            stages.add(new DeviceAccessTestResponse.Stage(
                    "DATA", "SKIPPED", "DEPENDENCY_SKIPPED", null, 0
            ));
        } else {
            stages.add(new DeviceAccessTestResponse.Stage(
                    "ACTIVITY", "PASSED", "FIRST_FRAME_CONFIRMED", evidence.firstSeenAt(), 0
            ));
            boolean fresh = isFresh(evidence.lastReceivedAt(), profile.offlineThresholdSeconds());
            stages.add(new DeviceAccessTestResponse.Stage(
                    "DATA", fresh ? "PASSED" : "FAILED", fresh ? "DATA_FRESH" : "DATA_STALE",
                    evidence.lastReceivedAt(), 0
            ));
        }
        return List.copyOf(stages);
    }

    private String overall(
            DeviceConnectionTestResponse endpoint,
            DeviceTransportSessionProbe.Result transport,
            DeviceActivityRepository.ActivityEvidence evidence,
            DeviceConnectionProfile profile,
            boolean activitySourceAvailable
    ) {
        if ("FAILED".equals(endpoint.status())) return "FAILED";
        if (!activitySourceAvailable) return "UNKNOWN";
        boolean mqtt = "DEVICE_TO_PLATFORM_MQTT".equals(profile.connectionMode());
        if (mqtt && "UNKNOWN".equals(transport.status())) return "UNKNOWN";
        if (mqtt && !"PASSED".equals(transport.status())) return "WAITING_FOR_DEVICE";
        if (evidence == null) {
            return "PASSED".equals(transport.status()) ? "TRANSPORT_ONLY" : "WAITING_FOR_DEVICE";
        }
        return isFresh(evidence.lastReceivedAt(), profile.offlineThresholdSeconds()) ? "ONLINE" : "STALE";
    }

    private DeviceAccessStatusResponse status(
            UUID deviceId,
            DeviceConnectionProfile profile,
            DeviceActivityRepository.ActivityEvidence evidence,
            boolean sourceAvailable
    ) {
        if (profile == null) {
            return new DeviceAccessStatusResponse(deviceId, "NOT_CONFIGURED", null, null, null,
                    "ACCESS_NOT_CONFIGURED");
        }
        if (!sourceAvailable) {
            return new DeviceAccessStatusResponse(deviceId, "UNKNOWN", null, null,
                    profile.offlineThresholdSeconds(), "ACTIVITY_SOURCE_UNAVAILABLE");
        }
        if (evidence == null) {
            return new DeviceAccessStatusResponse(deviceId, "WAITING_FOR_DEVICE", null, null,
                    profile.offlineThresholdSeconds(), "FIRST_FRAME_WAITING");
        }
        boolean fresh = isFresh(evidence.lastReceivedAt(), profile.offlineThresholdSeconds());
        return new DeviceAccessStatusResponse(
                deviceId, fresh ? "ONLINE" : "STALE", evidence.firstSeenAt(), evidence.lastReceivedAt(),
                profile.offlineThresholdSeconds(), fresh ? "ACCESS_ONLINE" : "DATA_STALE"
        );
    }

    private boolean isFresh(Instant lastReceivedAt, int thresholdSeconds) {
        return lastReceivedAt != null
                && !lastReceivedAt.isBefore(clock.instant().minusSeconds(thresholdSeconds));
    }

    private String message(
            String status,
            DeviceTransportSessionProbe.Result transport,
            DeviceActivityRepository.ActivityEvidence evidence
    ) {
        if ("WAITING_FOR_DEVICE".equals(status)
                && evidence != null
                && "WAITING".equals(transport.status())) {
            return transport.message();
        }
        return switch (status) {
            case "ONLINE" -> "ACCESS_ONLINE";
            case "STALE" -> "DATA_STALE";
            case "TRANSPORT_ONLY" -> "TRANSPORT_ONLY";
            case "WAITING_FOR_DEVICE" -> "FIRST_FRAME_WAITING";
            case "FAILED" -> "ACCESS_CHECK_FAILED";
            case "NOT_CONFIGURED" -> "ACCESS_NOT_CONFIGURED";
            default -> "ACCESS_UNKNOWN";
        };
    }

    /**
     * 查询遥测活动证据；查询源未启用或暂时异常时按未知降级。
     *
     * @param deviceIds 设备标识集合
     * @return 查询结果及数据源可用性
     */
    private ActivityQueryResult queryActivity(Collection<UUID> deviceIds) {
        if (activityRepository.isEmpty()) {
            return new ActivityQueryResult(Map.of(), false);
        }
        try {
            return new ActivityQueryResult(activityRepository.get().findByDeviceIds(deviceIds), true);
        } catch (RuntimeException exception) {
            return new ActivityQueryResult(Map.of(), false);
        }
    }

    private void requireDevice(UUID deviceId) {
        if (deviceMapper.findDeviceById(deviceId).isEmpty()) {
            throw new MineException(DEVICE_NOT_FOUND, Map.of("deviceId", deviceId));
        }
    }

    /**
     * 遥测活动证据查询结果。
     *
     * @param evidence 按设备索引的活动证据
     * @param sourceAvailable 遥测查询源是否可用
     */
    private record ActivityQueryResult(
            Map<UUID, DeviceActivityRepository.ActivityEvidence> evidence,
            boolean sourceAvailable
    ) {
    }
}
