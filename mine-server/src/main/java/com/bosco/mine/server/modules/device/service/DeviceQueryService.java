package com.bosco.mine.server.modules.device.service;

import com.bosco.mine.server.bootstrap.web.PageResponse;
import com.bosco.mine.server.modules.device.dto.DeviceResponse;
import com.bosco.mine.server.modules.device.dto.ProductResponse;
import com.bosco.mine.server.modules.device.model.Device;
import com.bosco.mine.server.modules.device.model.Product;
import com.bosco.mine.server.modules.device.repository.DeviceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 提供产品和设备台账只读查询。
 */
@Service
public class DeviceQueryService {

    private final DeviceMapper mapper;

    /**
     * 创建设备查询服务。
     *
     * @param mapper 设备数据访问接口
     */
    public DeviceQueryService(DeviceMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 分页查询产品。
     *
     * @param keyword 名称或编码关键词
     * @param status  产品状态
     * @param page    页码
     * @param size    每页数量
     * @return 产品分页数据
     */
    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> findProducts(
            String keyword,
            String status,
            int page,
            int size
    ) {
        String normalizedKeyword = normalizeOptional(keyword);
        String normalizedStatus = normalizeOptional(status);
        int offset = Math.multiplyExact(page - 1, size);
        List<ProductResponse> items = mapper.findProducts(normalizedKeyword, normalizedStatus, offset, size)
                .stream()
                .map(DeviceQueryService::toProductResponse)
                .toList();
        return new PageResponse<>(
                items,
                page,
                size,
                mapper.countProducts(normalizedKeyword, normalizedStatus)
        );
    }

    /**
     * 分页查询设备。
     *
     * @param productId 所属产品筛选
     * @param keyword   名称或编码关键词
     * @param status    设备状态
     * @param page      页码
     * @param size      每页数量
     * @return 设备分页数据
     */
    @Transactional(readOnly = true)
    public PageResponse<DeviceResponse> findDevices(
            UUID productId,
            String keyword,
            String status,
            int page,
            int size
    ) {
        String normalizedKeyword = normalizeOptional(keyword);
        String normalizedStatus = normalizeOptional(status);
        int offset = Math.multiplyExact(page - 1, size);
        List<DeviceResponse> items = mapper.findDevices(
                        productId,
                        normalizedKeyword,
                        normalizedStatus,
                        offset,
                        size
                ).stream()
                .map(DeviceQueryService::toDeviceResponse)
                .toList();
        return new PageResponse<>(
                items,
                page,
                size,
                mapper.countDevices(productId, normalizedKeyword, normalizedStatus)
        );
    }

    /**
     * 将产品模型转换为接口响应。
     *
     * @param product 产品模型
     * @return 产品响应
     */
    public static ProductResponse toProductResponse(Product product) {
        return new ProductResponse(
                product.id(),
                product.productKey(),
                product.name(),
                product.deviceType(),
                product.status(),
                product.archivedAt(),
                product.createdAt(),
                product.updatedAt(),
                product.version()
        );
    }

    /**
     * 将设备模型转换为接口响应。
     *
     * @param device 设备模型
     * @return 设备响应
     */
    public static DeviceResponse toDeviceResponse(Device device) {
        return new DeviceResponse(
                device.id(),
                device.productId(),
                device.deviceCode(),
                device.name(),
                device.status(),
                device.archivedAt(),
                device.createdAt(),
                device.updatedAt(),
                device.version()
        );
    }

    private String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }
}
