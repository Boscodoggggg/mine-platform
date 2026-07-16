package com.bosco.mine.server.modules.device.infrastructure;

import com.bosco.mine.server.modules.device.model.DeviceCredential;
import com.bosco.mine.server.modules.device.repository.DeviceMapper;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 使用 RabbitMQ 管理面查询 MQTT 设备的当前传输会话。
 */
@Component
public class RabbitDeviceTransportSessionProbe implements DeviceTransportSessionProbe {

    private final DeviceMapper deviceMapper;
    private final RabbitUserAdminClient rabbitAdminClient;

    /**
     * 创建 MQTT 传输会话探针。
     *
     * @param deviceMapper 设备数据接口
     * @param rabbitAdminClient RabbitMQ 管理客户端
     */
    public RabbitDeviceTransportSessionProbe(
            DeviceMapper deviceMapper,
            RabbitUserAdminClient rabbitAdminClient
    ) {
        this.deviceMapper = deviceMapper;
        this.rabbitAdminClient = rabbitAdminClient;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Result probe(UUID deviceId, String connectionMode) {
        if (!"DEVICE_TO_PLATFORM_MQTT".equals(connectionMode)) {
            return new Result("UNKNOWN", "TRANSPORT_SOURCE_UNAVAILABLE", null);
        }
        Optional<DeviceCredential> credential = deviceMapper.findActiveCredentialByDeviceId(deviceId);
        if (credential.isEmpty()) {
            return new Result("WAITING", "CREDENTIAL_NOT_ACTIVE", null);
        }
        try {
            List<RabbitUserAdminClient.ConnectionSummary> connections =
                    rabbitAdminClient.findConnections(credential.get().username());
            return connections.stream()
                    .max(Comparator.comparing(RabbitUserAdminClient.ConnectionSummary::connectedAt))
                    .map(connection -> new Result(
                            "PASSED", "TRANSPORT_CONNECTED", connection.connectedAt()
                    ))
                    .orElseGet(() -> new Result("WAITING", "TRANSPORT_WAITING", null));
        } catch (RuntimeException exception) {
            return new Result("UNKNOWN", "TRANSPORT_PROBE_FAILED", null);
        }
    }
}
