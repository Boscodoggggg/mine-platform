package com.bosco.mine.server.modules.device.service;

import cn.dev33.satoken.stp.StpUtil;
import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.server.modules.device.dto.DeviceCredentialResponse;
import com.bosco.mine.server.modules.device.infrastructure.RabbitUserAdminClient;
import com.bosco.mine.server.modules.device.model.Device;
import com.bosco.mine.server.modules.device.model.DeviceCredential;
import com.bosco.mine.server.modules.device.model.Product;
import com.bosco.mine.server.modules.device.repository.DeviceMapper;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * 负责设备 MQTT 凭据的一次性签发、轮换和吊销。
 */
@Service
public class DeviceCredentialService {

    private static final ErrorCode DEVICE_NOT_FOUND =
            new ErrorCode("DEVICE-404-002", "设备不存在");
    private static final ErrorCode CREDENTIAL_NOT_FOUND =
            new ErrorCode("DEVICE-404-003", "设备凭据不存在");
    private static final ErrorCode CREDENTIAL_CONFLICT =
            new ErrorCode("DEVICE-409-005", "设备已有活动凭据");
    private static final ErrorCode VERSION_CONFLICT =
            new ErrorCode("DEVICE-409-003", "数据已被其他管理员修改，请刷新后重试");
    private static final ErrorCode DEVICE_NOT_ENABLED =
            new ErrorCode("DEVICE-422-002", "只有启用设备可以签发凭据");

    private final DeviceMapper mapper;
    private final RabbitUserAdminClient rabbitAdminClient;
    private final PasswordEncoder passwordEncoder;
    private final OperationAuditService auditService;
    private final IdGenerator idGenerator = IdGenerator.timeOrdered();
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 创建设备凭据服务。
     *
     * @param mapper            设备数据访问接口
     * @param rabbitAdminClient RabbitMQ 用户管理客户端
     * @param passwordEncoder   密钥散列器
     * @param auditService      操作审计服务
     */
    public DeviceCredentialService(
            DeviceMapper mapper,
            RabbitUserAdminClient rabbitAdminClient,
            PasswordEncoder passwordEncoder,
            OperationAuditService auditService
    ) {
        this.mapper = mapper;
        this.rabbitAdminClient = rabbitAdminClient;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    /**
     * 为启用设备签发唯一活动凭据，明文密钥只在本次返回。
     *
     * @param deviceId 设备标识
     * @return 一次性凭据响应
     */
    @Transactional
    public DeviceCredentialResponse issue(UUID deviceId) {
        Device device = enabledDevice(deviceId);
        if (mapper.activeCredentialExists(deviceId)) {
            throw new MineException(CREDENTIAL_CONFLICT, Map.of("deviceId", deviceId));
        }
        Product product = mapper.findProductById(device.productId())
                .orElseThrow(() -> new MineException(DEVICE_NOT_FOUND, Map.of("deviceId", deviceId)));
        UUID credentialId = idGenerator.next();
        String username = "device-" + credentialId.toString().replace("-", "");
        String secret = newSecret();
        String topicRoot = "mine/v1/" + product.productKey() + "/" + username;

        rabbitAdminClient.provision(product.productKey(), username, secret);
        try {
            mapper.insertCredential(
                    credentialId,
                    deviceId,
                    username,
                    passwordEncoder.encode(secret),
                    secretHint(secret),
                    topicRoot,
                    currentOperatorId()
            );
            audit("ISSUE_DEVICE_CREDENTIAL", "device:credential:create", credentialId, Map.of(
                    "deviceId", deviceId,
                    "username", username,
                    "topicRoot", topicRoot,
                    "version", 0
            ));
            return response(credentialId, deviceId, username, secret, topicRoot, 0);
        } catch (DuplicateKeyException exception) {
            rabbitAdminClient.deleteQuietly(username);
            throw new MineException(CREDENTIAL_CONFLICT, Map.of("deviceId", deviceId));
        } catch (RuntimeException exception) {
            rabbitAdminClient.deleteQuietly(username);
            throw exception;
        }
    }

    /**
     * 轮换活动凭据密钥并立即更新 Broker 密码。
     *
     * @param credentialId 凭据标识
     * @param version      当前版本号
     * @return 只包含一次新明文密钥的响应
     */
    @Transactional
    public DeviceCredentialResponse rotate(UUID credentialId, long version) {
        DeviceCredential credential = activeCredential(credentialId);
        String secret = newSecret();
        if (mapper.rotateCredential(
                credentialId,
                passwordEncoder.encode(secret),
                secretHint(secret),
                version,
                currentOperatorId()
        ) == 0) {
            throw new MineException(VERSION_CONFLICT, Map.of("credentialId", credentialId));
        }
        long nextVersion = version + 1;
        audit("ROTATE_DEVICE_CREDENTIAL", "device:credential:rotate", credentialId, Map.of(
                "deviceId", credential.deviceId(),
                "username", credential.username(),
                "topicRoot", credential.topicRoot(),
                "version", nextVersion
        ));
        rabbitAdminClient.changePassword(credential.username(), secret);
        return response(
                credential.id(), credential.deviceId(), credential.username(),
                secret, credential.topicRoot(), nextVersion
        );
    }

    /**
     * 吊销活动凭据并删除 Broker 用户。
     *
     * @param credentialId 凭据标识
     * @param version      当前版本号
     */
    @Transactional
    public void revoke(UUID credentialId, long version) {
        DeviceCredential credential = activeCredential(credentialId);
        if (mapper.revokeCredential(credentialId, version, currentOperatorId()) == 0) {
            throw new MineException(VERSION_CONFLICT, Map.of("credentialId", credentialId));
        }
        audit("REVOKE_DEVICE_CREDENTIAL", "device:credential:revoke", credentialId, Map.of(
                "deviceId", credential.deviceId(),
                "username", credential.username(),
                "topicRoot", credential.topicRoot(),
                "version", version + 1
        ));
        rabbitAdminClient.delete(credential.username());
    }

    private Device enabledDevice(UUID deviceId) {
        Device device = mapper.findDeviceById(deviceId)
                .orElseThrow(() -> new MineException(DEVICE_NOT_FOUND, Map.of("deviceId", deviceId)));
        if (!"ENABLED".equals(device.status())) {
            throw new MineException(DEVICE_NOT_ENABLED, Map.of("deviceId", deviceId));
        }
        return device;
    }

    private DeviceCredential activeCredential(UUID credentialId) {
        DeviceCredential credential = mapper.findCredentialById(credentialId)
                .orElseThrow(() -> new MineException(
                        CREDENTIAL_NOT_FOUND, Map.of("credentialId", credentialId)
                ));
        if (!"ACTIVE".equals(credential.status())) {
            throw new MineException(CREDENTIAL_NOT_FOUND, Map.of("credentialId", credentialId));
        }
        return credential;
    }

    private DeviceCredentialResponse response(
            UUID id,
            UUID deviceId,
            String username,
            String secret,
            String topicRoot,
            long version
    ) {
        return new DeviceCredentialResponse(
                id,
                deviceId,
                username,
                username,
                secret,
                topicRoot,
                topicRoot + "/up",
                topicRoot + "/down",
                version
        );
    }

    private String newSecret() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String secretHint(String secret) {
        return secret.substring(secret.length() - 8);
    }

    private UUID currentOperatorId() {
        try {
            return UUID.fromString(StpUtil.getLoginIdAsString());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private void audit(
            String action,
            String permission,
            UUID credentialId,
            Map<String, ?> summary
    ) {
        auditService.recordSuccess(
                "DEVICE_MANAGEMENT",
                action,
                permission,
                "DEVICE_CREDENTIAL",
                credentialId,
                Map.of(),
                summary
        );
    }
}
