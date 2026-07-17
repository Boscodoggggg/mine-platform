package com.bosco.mine.server.modules.device.service;

import cn.dev33.satoken.stp.StpUtil;
import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.model.strategy.AlarmStrategyDefinition;
import com.bosco.mine.model.strategy.DataQualityStrategyDefinition;
import com.bosco.mine.model.strategy.RetentionStrategyDefinition;
import com.bosco.mine.server.modules.device.dto.DeviceResponse;
import com.bosco.mine.server.modules.device.dto.ProductResponse;
import com.bosco.mine.server.modules.device.model.Device;
import com.bosco.mine.server.modules.device.model.Product;
import com.bosco.mine.server.modules.device.repository.DeviceMapper;
import com.bosco.mine.server.modules.strategy.service.StrategyRuntimeSnapshotService;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 处理产品和设备台账写操作，并追加脱敏操作审计。
 */
@Service
public class DeviceCommandService {

    private static final ErrorCode PRODUCT_NOT_FOUND =
            new ErrorCode("DEVICE-404-001", "产品不存在");
    private static final ErrorCode DEVICE_NOT_FOUND =
            new ErrorCode("DEVICE-404-002", "设备不存在");
    private static final ErrorCode PRODUCT_KEY_CONFLICT =
            new ErrorCode("DEVICE-409-001", "产品编码已存在");
    private static final ErrorCode DEVICE_CODE_CONFLICT =
            new ErrorCode("DEVICE-409-002", "设备编码已存在");
    private static final ErrorCode VERSION_CONFLICT =
            new ErrorCode("DEVICE-409-003", "数据已被其他管理员修改，请刷新后重试");
    private static final ErrorCode PRODUCT_IN_USE =
            new ErrorCode("DEVICE-409-004", "产品下仍有未归档设备，请先归档设备");
    private static final ErrorCode PRODUCT_NOT_ACTIVE =
            new ErrorCode("DEVICE-422-001", "只有启用产品可以创建设备");

    private final DeviceMapper mapper;
    private final OperationAuditService auditService;
    private final StrategyRuntimeSnapshotService runtimeSnapshotService;
    private final IdGenerator idGenerator = IdGenerator.timeOrdered();

    /**
     * 创建设备写服务。
     *
     * @param mapper       设备数据访问接口
     * @param auditService 操作审计服务
     * @param runtimeSnapshotService 运行策略增量快照服务
     */
    public DeviceCommandService(
            DeviceMapper mapper,
            OperationAuditService auditService,
            StrategyRuntimeSnapshotService runtimeSnapshotService
    ) {
        this.mapper = mapper;
        this.auditService = auditService;
        this.runtimeSnapshotService = runtimeSnapshotService;
    }

    /**
     * 创建产品。
     *
     * @param productKey 产品编码
     * @param name       产品名称
     * @param deviceType 设备类型
     * @param status     初始状态
     * @return 新产品快照
     */
    @Transactional
    public ProductResponse createProduct(
            String productKey,
            String name,
            String deviceType,
            String status
    ) {
        String normalizedKey = productKey.strip().toLowerCase(Locale.ROOT);
        if (mapper.productKeyExists(normalizedKey)) {
            throw new MineException(PRODUCT_KEY_CONFLICT, Map.of("productKey", normalizedKey));
        }
        UUID productId = idGenerator.next();
        try {
            mapper.insertProduct(
                    productId,
                    normalizedKey,
                    name.strip(),
                    deviceType.strip().toUpperCase(Locale.ROOT),
                    normalizeProductStatus(status),
                    currentOperatorId()
            );
        } catch (DuplicateKeyException exception) {
            throw new MineException(PRODUCT_KEY_CONFLICT, Map.of("productKey", normalizedKey));
        }
        ProductResponse created = productById(productId);
        audit("CREATE_PRODUCT", "device:product:create", "PRODUCT", productId, Map.of(), productSummary(created));
        return created;
    }

    /**
     * 修改产品可变信息。
     *
     * @param productId 产品标识
     * @param name       产品名称
     * @param deviceType 设备类型
     * @param status     产品状态
     * @param version    期望版本号
     * @return 更新后的产品
     */
    @Transactional
    public ProductResponse updateProduct(
            UUID productId,
            String name,
            String deviceType,
            String status,
            long version
    ) {
        ProductResponse before = productById(productId);
        int affected = mapper.updateProduct(
                productId,
                name.strip(),
                deviceType.strip().toUpperCase(Locale.ROOT),
                normalizeProductStatus(status),
                version,
                currentOperatorId()
        );
        if (affected == 0) {
            throw new MineException(VERSION_CONFLICT, Map.of("productId", productId));
        }
        ProductResponse updated = productById(productId);
        audit("UPDATE_PRODUCT", "device:product:update", "PRODUCT", productId,
                productSummary(before), productSummary(updated));
        return updated;
    }

    /**
     * 归档没有活动设备的产品。
     *
     * @param productId 产品标识
     * @param version   期望版本号
     */
    @Transactional
    public void archiveProduct(UUID productId, long version) {
        ProductResponse before = productById(productId);
        if (mapper.countUnarchivedDevices(productId) > 0) {
            throw new MineException(PRODUCT_IN_USE, Map.of("productId", productId));
        }
        if (mapper.archiveProduct(productId, version, currentOperatorId()) == 0) {
            throw new MineException(VERSION_CONFLICT, Map.of("productId", productId));
        }
        ProductResponse archived = productById(productId);
        audit("ARCHIVE_PRODUCT", "device:product:archive", "PRODUCT", productId,
                productSummary(before), productSummary(archived));
    }

    /**
     * 在启用产品下创建设备。
     *
     * @param productId  产品标识
     * @param deviceCode 设备编码
     * @param name       设备名称
     * @param status     初始状态
     * @return 新设备快照
     */
    @Transactional
    public DeviceResponse createDevice(
            UUID productId,
            String deviceCode,
            String name,
            String status
    ) {
        mapper.lockProduct(productId);
        Product product = mapper.findProductById(productId)
                .orElseThrow(() -> new MineException(PRODUCT_NOT_FOUND, Map.of("productId", productId)));
        if (!"ACTIVE".equals(product.status())) {
            throw new MineException(PRODUCT_NOT_ACTIVE, Map.of("productId", productId));
        }
        String normalizedCode = deviceCode.strip();
        if (mapper.deviceCodeExists(normalizedCode)) {
            throw new MineException(DEVICE_CODE_CONFLICT, Map.of("deviceCode", normalizedCode));
        }
        UUID deviceId = idGenerator.next();
        try {
            mapper.insertDevice(
                    deviceId,
                    productId,
                    normalizedCode,
                    name.strip(),
                    normalizeDeviceStatus(status),
                    currentOperatorId()
            );
        } catch (DuplicateKeyException exception) {
            throw new MineException(DEVICE_CODE_CONFLICT, Map.of("deviceCode", normalizedCode));
        }
        DeviceResponse created = deviceById(deviceId);
        audit("CREATE_DEVICE", "device:device:create", "DEVICE", deviceId, Map.of(), deviceSummary(created));
        captureRuntimeDeltas(
                productId, deviceId, StrategyRuntimeSnapshotService.ChangeReason.DEVICE_CREATE);
        return created;
    }

    /**
     * 修改设备可变信息。
     *
     * @param deviceId 设备标识
     * @param name     设备名称
     * @param status   设备状态
     * @param version  期望版本号
     * @return 更新后的设备
     */
    @Transactional
    public DeviceResponse updateDevice(UUID deviceId, String name, String status, long version) {
        DeviceResponse before = deviceById(deviceId);
        int affected = mapper.updateDevice(
                deviceId,
                name.strip(),
                normalizeDeviceStatus(status),
                version,
                currentOperatorId()
        );
        if (affected == 0) {
            throw new MineException(VERSION_CONFLICT, Map.of("deviceId", deviceId));
        }
        DeviceResponse updated = deviceById(deviceId);
        audit("UPDATE_DEVICE", "device:device:update", "DEVICE", deviceId,
                deviceSummary(before), deviceSummary(updated));
        return updated;
    }

    /**
     * 归档设备并保留台账。
     *
     * @param deviceId 设备标识
     * @param version  期望版本号
     */
    @Transactional
    public void archiveDevice(UUID deviceId, long version) {
        DeviceResponse before = deviceById(deviceId);
        mapper.lockProduct(before.productId());
        if (mapper.archiveDevice(deviceId, version, currentOperatorId()) == 0) {
            throw new MineException(VERSION_CONFLICT, Map.of("deviceId", deviceId));
        }
        DeviceResponse archived = deviceById(deviceId);
        audit("ARCHIVE_DEVICE", "device:device:archive", "DEVICE", deviceId,
                deviceSummary(before), deviceSummary(archived));
        captureRuntimeDeltas(
                before.productId(), deviceId,
                StrategyRuntimeSnapshotService.ChangeReason.DEVICE_ARCHIVE);
    }

    private void captureRuntimeDeltas(
            UUID productId,
            UUID deviceId,
            StrategyRuntimeSnapshotService.ChangeReason reason
    ) {
        java.util.List<UUID> deviceIds = java.util.List.of(deviceId);
        runtimeSnapshotService.captureDelta(
                productId, DataQualityStrategyDefinition.TYPE, deviceIds, reason);
        runtimeSnapshotService.captureDelta(
                productId, AlarmStrategyDefinition.TYPE, deviceIds, reason);
        runtimeSnapshotService.captureDelta(
                productId, RetentionStrategyDefinition.TYPE, deviceIds, reason);
    }

    private ProductResponse productById(UUID productId) {
        return mapper.findProductById(productId)
                .map(DeviceQueryService::toProductResponse)
                .orElseThrow(() -> new MineException(PRODUCT_NOT_FOUND, Map.of("productId", productId)));
    }

    private DeviceResponse deviceById(UUID deviceId) {
        return mapper.findDeviceById(deviceId)
                .map(DeviceQueryService::toDeviceResponse)
                .orElseThrow(() -> new MineException(DEVICE_NOT_FOUND, Map.of("deviceId", deviceId)));
    }

    private String normalizeProductStatus(String status) {
        String normalized = status.strip().toUpperCase(Locale.ROOT);
        if (!"DRAFT".equals(normalized) && !"ACTIVE".equals(normalized)) {
            throw new IllegalArgumentException("产品状态必须为 DRAFT 或 ACTIVE");
        }
        return normalized;
    }

    private String normalizeDeviceStatus(String status) {
        String normalized = status.strip().toUpperCase(Locale.ROOT);
        if (!"ENABLED".equals(normalized) && !"DISABLED".equals(normalized)) {
            throw new IllegalArgumentException("设备状态必须为 ENABLED 或 DISABLED");
        }
        return normalized;
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
            String resourceType,
            UUID resourceId,
            Map<String, ?> before,
            Map<String, ?> after
    ) {
        auditService.recordSuccess(
                "DEVICE_MANAGEMENT",
                action,
                permission,
                resourceType,
                resourceId,
                before,
                after
        );
    }

    private Map<String, ?> productSummary(ProductResponse product) {
        return Map.of(
                "productKey", product.productKey(),
                "name", product.name(),
                "deviceType", product.deviceType(),
                "status", product.status(),
                "version", product.version()
        );
    }

    private Map<String, ?> deviceSummary(DeviceResponse device) {
        return Map.of(
                "productId", device.productId(),
                "deviceCode", device.deviceCode(),
                "name", device.name(),
                "status", device.status(),
                "version", device.version()
        );
    }
}
