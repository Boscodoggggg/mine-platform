package com.bosco.mine.server.modules.device.service;

import cn.dev33.satoken.stp.StpUtil;
import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.server.modules.device.dto.DeviceConnectionProfileRequest;
import com.bosco.mine.server.modules.device.dto.DeviceConnectionProfileResponse;
import com.bosco.mine.server.modules.device.dto.DeviceConnectionTestResponse;
import com.bosco.mine.server.modules.device.infrastructure.DeviceConnectionProbe;
import com.bosco.mine.server.modules.device.model.Device;
import com.bosco.mine.server.modules.device.model.DeviceConnectionProfile;
import com.bosco.mine.server.modules.device.repository.DeviceConnectionProfileMapper;
import com.bosco.mine.server.modules.device.repository.DeviceMapper;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 管理设备网络接入配置并执行分层连通性诊断。
 */
@Service
public class DeviceConnectionService {

    private static final ErrorCode DEVICE_NOT_FOUND = new ErrorCode("DEVICE-404-002", "设备不存在");
    private static final ErrorCode PROFILE_NOT_FOUND = new ErrorCode("DEVICE-404-004", "设备接入配置不存在");
    private static final ErrorCode VERSION_CONFLICT =
            new ErrorCode("DEVICE-409-003", "数据已被其他管理员修改，请刷新后重试");

    private final DeviceConnectionProfileMapper profileMapper;
    private final DeviceMapper deviceMapper;
    private final DeviceConnectionProbe probe;
    private final OperationAuditService auditService;
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator = IdGenerator.timeOrdered();

    /**
     * 创建设备接入服务。
     *
     * @param profileMapper 接入配置数据接口
     * @param deviceMapper  设备台账数据接口
     * @param probe         网络分层探针
     * @param auditService  操作审计服务
     * @param objectMapper  JSON 映射器
     */
    public DeviceConnectionService(
            DeviceConnectionProfileMapper profileMapper,
            DeviceMapper deviceMapper,
            DeviceConnectionProbe probe,
            OperationAuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.profileMapper = profileMapper;
        this.deviceMapper = deviceMapper;
        this.probe = probe;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询设备接入配置。
     *
     * @param deviceId 设备标识
     * @return 不含凭据的接入配置
     */
    @Transactional(readOnly = true)
    public DeviceConnectionProfileResponse get(UUID deviceId) {
        requireDevice(deviceId);
        return toResponse(requireProfile(deviceId));
    }

    /**
     * 创建或使用乐观锁修改设备接入配置。
     *
     * @param deviceId 设备标识
     * @param request  保存请求
     * @return 保存后的接入配置
     */
    @Transactional
    public DeviceConnectionProfileResponse save(UUID deviceId, DeviceConnectionProfileRequest request) {
        Device device = requireDevice(deviceId);
        DeviceConnectionProfile existing = profileMapper.findByDeviceId(deviceId).orElse(null);
        Instant now = Instant.now();
        DeviceConnectionProfile candidate = new DeviceConnectionProfile(
                existing == null ? idGenerator.next() : existing.id(),
                deviceId,
                request.connectionMode(),
                request.host().strip(),
                request.port(),
                Boolean.TRUE.equals(request.tlsEnabled()),
                normalizeNullable(request.tlsServerName()),
                request.connectTimeoutMillis(),
                request.keepAliveSeconds(),
                request.offlineThresholdSeconds(),
                "NOT_TESTED", null, null, null, "[]",
                existing == null ? now : existing.createdAt(), now,
                existing == null ? 0 : existing.version()
        );
        Map<String, ?> before = existing == null ? Map.of() : summary(existing);
        try {
            if (existing == null) {
                if (request.version() != 0) {
                    throw conflict(deviceId);
                }
                profileMapper.insert(candidate, currentOperatorId());
            } else if (profileMapper.update(candidate, request.version(), currentOperatorId()) == 0) {
                throw conflict(deviceId);
            }
        } catch (DuplicateKeyException exception) {
            throw conflict(deviceId);
        }
        DeviceConnectionProfile saved = profileMapper.findByDeviceId(deviceId).orElse(candidate);
        auditService.recordSuccess(
                "DEVICE_MANAGEMENT", "SAVE_DEVICE_CONNECTION_PROFILE", "device:connection:update",
                "DEVICE_CONNECTION_PROFILE", saved.id(), before, summary(saved)
        );
        return toResponse(saved);
    }

    /**
     * 执行配置、DNS、TCP、TLS 和设备确认分层诊断。
     *
     * @param deviceId 设备标识
     * @return 本次诊断结果
     */
    public DeviceConnectionTestResponse test(UUID deviceId) {
        requireDevice(deviceId);
        DeviceConnectionProfile profile = requireProfile(deviceId);
        Instant startedAt = Instant.now();
        List<DeviceConnectionTestResponse.Stage> stages = new ArrayList<>();
        stages.add(new DeviceConnectionTestResponse.Stage("CONFIG", "PASSED", "CONFIG_OK", 0));
        stages.addAll(probe.probe(profile));
        if (stages.stream().noneMatch(stage -> "DEVICE_CONFIRMATION".equals(stage.stage()))) {
            boolean platformInitiated = "PLATFORM_TO_DEVICE_TCP".equals(profile.connectionMode());
            stages.add(platformInitiated
                    ? new DeviceConnectionTestResponse.Stage(
                            "DEVICE_CONFIRMATION", "PASSED", "ACTIVE_ENDPOINT_CONFIRMED", 0
                    )
                    : new DeviceConnectionTestResponse.Stage(
                            "DEVICE_CONFIRMATION", "SKIPPED", "FIRST_MESSAGE_REQUIRED", 0
                    ));
        }
        long duration = Math.max(0, Duration.between(startedAt, Instant.now()).toMillis());
        String status = overallStatus(stages);
        String message = switch (status) {
            case "PASSED" -> "TEST_PASSED";
            case "PARTIAL" -> "TEST_PARTIAL";
            default -> "TEST_FAILED";
        };
        if (profileMapper.updateTestResult(
                profile.id(), status, startedAt, duration, message,
                writeStages(stages), profile.version(), currentOperatorId()
        ) == 0) {
            throw conflict(deviceId);
        }
        auditService.recordSuccess(
                "DEVICE_MANAGEMENT", "TEST_DEVICE_CONNECTION", "device:connection:test",
                "DEVICE_CONNECTION_PROFILE", profile.id(), Map.of(),
                Map.of("deviceId", deviceId, "status", status, "durationMillis", duration)
        );
        return new DeviceConnectionTestResponse(deviceId, status, startedAt, duration, message, List.copyOf(stages));
    }

    private Device requireDevice(UUID deviceId) {
        return deviceMapper.findDeviceById(deviceId)
                .orElseThrow(() -> new MineException(DEVICE_NOT_FOUND, Map.of("deviceId", deviceId)));
    }

    private DeviceConnectionProfile requireProfile(UUID deviceId) {
        return profileMapper.findByDeviceId(deviceId)
                .orElseThrow(() -> new MineException(PROFILE_NOT_FOUND, Map.of("deviceId", deviceId)));
    }

    private DeviceConnectionProfileResponse toResponse(DeviceConnectionProfile profile) {
        return new DeviceConnectionProfileResponse(
                profile.id(), profile.deviceId(), profile.connectionMode(), profile.host(), profile.port(),
                profile.tlsEnabled(), profile.tlsServerName(), profile.connectTimeoutMillis(),
                profile.keepAliveSeconds(), profile.offlineThresholdSeconds(), profile.lastTestStatus(),
                profile.lastTestAt(), profile.lastTestDurationMillis(), profile.lastTestMessage(),
                readStages(profile.lastTestStages()), profile.createdAt(), profile.updatedAt(), profile.version()
        );
    }

    private String overallStatus(List<DeviceConnectionTestResponse.Stage> stages) {
        if (stages.stream().anyMatch(stage -> "FAILED".equals(stage.status()))) {
            return "FAILED";
        }
        if (stages.stream().anyMatch(stage ->
                "DEVICE_CONFIRMATION".equals(stage.stage()) && "SKIPPED".equals(stage.status()))) {
            return "PARTIAL";
        }
        return "PASSED";
    }

    private String writeStages(List<DeviceConnectionTestResponse.Stage> stages) {
        try {
            return objectMapper.writeValueAsString(stages);
        } catch (JacksonException exception) {
            throw new IllegalStateException("设备接入诊断结果序列化失败", exception);
        }
    }

    private List<DeviceConnectionTestResponse.Stage> readStages(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JacksonException exception) {
            throw new IllegalStateException("设备接入诊断结果解析失败", exception);
        }
    }

    private Map<String, ?> summary(DeviceConnectionProfile profile) {
        return Map.of(
                "deviceId", profile.deviceId(),
                "connectionMode", profile.connectionMode(),
                "host", profile.host(),
                "port", profile.port(),
                "tlsEnabled", profile.tlsEnabled(),
                "version", profile.version()
        );
    }

    private String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private MineException conflict(UUID deviceId) {
        return new MineException(VERSION_CONFLICT, Map.of("deviceId", deviceId));
    }

    private UUID currentOperatorId() {
        try {
            return UUID.fromString(StpUtil.getLoginIdAsString());
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
