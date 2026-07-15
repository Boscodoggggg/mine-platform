package com.bosco.mine.server.modules.device.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.bootstrap.web.PageResponse;
import com.bosco.mine.server.modules.device.dto.DeviceCreateRequest;
import com.bosco.mine.server.modules.device.dto.DeviceResponse;
import com.bosco.mine.server.modules.device.dto.DeviceUpdateRequest;
import com.bosco.mine.server.modules.device.dto.ProductCreateRequest;
import com.bosco.mine.server.modules.device.dto.ProductResponse;
import com.bosco.mine.server.modules.device.dto.ProductUpdateRequest;
import com.bosco.mine.server.modules.device.service.DeviceCommandService;
import com.bosco.mine.server.modules.device.service.DeviceQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

/**
 * 提供产品和设备台账的查询、维护与归档接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class DeviceController {

    private static final ErrorCode PAGE_INVALID =
            new ErrorCode("REQUEST-400-002", "分页参数不合法");

    private final DeviceCommandService commandService;
    private final DeviceQueryService queryService;

    /**
     * 创建设备管理控制器。
     *
     * @param commandService 设备写服务
     * @param queryService   设备查询服务
     */
    public DeviceController(DeviceCommandService commandService, DeviceQueryService queryService) {
        this.commandService = commandService;
        this.queryService = queryService;
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
    @GetMapping("/products")
    @SaCheckPermission("device:product:view")
    public PageResponse<ProductResponse> findProducts(
            @RequestParam(required = false) @Size(max = 128) String keyword,
            @RequestParam(required = false)
            @Pattern(regexp = "DRAFT|ACTIVE|ARCHIVED") String status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        validatePage(page, size);
        return queryService.findProducts(keyword, status, page, size);
    }

    /**
     * 创建产品。
     *
     * @param request 产品创建请求
     * @return 新产品和资源地址
     */
    @PostMapping("/products")
    @SaCheckPermission("device:product:create")
    public ResponseEntity<ProductResponse> createProduct(@Valid @RequestBody ProductCreateRequest request) {
        ProductResponse created = commandService.createProduct(
                request.productKey(),
                request.name(),
                request.deviceType(),
                request.status()
        );
        URI location = UriComponentsBuilder.fromPath("/api/v1/products/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * 修改产品可变信息。
     *
     * @param productId 产品标识
     * @param request   产品修改请求
     * @return 更新后的产品
     */
    @PutMapping("/products/{id}")
    @SaCheckPermission("device:product:update")
    public ProductResponse updateProduct(
            @PathVariable("id") UUID productId,
            @Valid @RequestBody ProductUpdateRequest request
    ) {
        return commandService.updateProduct(
                productId,
                request.name(),
                request.deviceType(),
                request.status(),
                request.version()
        );
    }

    /**
     * 归档产品。
     *
     * @param productId 产品标识
     * @param request   归档请求
     * @return 空响应
     */
    @PostMapping("/products/{id}/archive")
    @SaCheckPermission("device:product:archive")
    public ResponseEntity<Void> archiveProduct(
            @PathVariable("id") UUID productId,
            @Valid @RequestBody ArchiveRequest request
    ) {
        commandService.archiveProduct(productId, request.version());
        return ResponseEntity.noContent().build();
    }

    /**
     * 分页查询设备。
     *
     * @param productId 产品筛选
     * @param keyword   名称或编码关键词
     * @param status    设备状态
     * @param page      页码
     * @param size      每页数量
     * @return 设备分页数据
     */
    @GetMapping("/devices")
    @SaCheckPermission("device:device:view")
    public PageResponse<DeviceResponse> findDevices(
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) @Size(max = 128) String keyword,
            @RequestParam(required = false)
            @Pattern(regexp = "ENABLED|DISABLED|ARCHIVED") String status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        validatePage(page, size);
        return queryService.findDevices(productId, keyword, status, page, size);
    }

    /**
     * 创建设备。
     *
     * @param request 设备创建请求
     * @return 新设备和资源地址
     */
    @PostMapping("/devices")
    @SaCheckPermission("device:device:create")
    public ResponseEntity<DeviceResponse> createDevice(@Valid @RequestBody DeviceCreateRequest request) {
        DeviceResponse created = commandService.createDevice(
                request.productId(),
                request.deviceCode(),
                request.name(),
                request.status()
        );
        URI location = UriComponentsBuilder.fromPath("/api/v1/devices/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * 修改设备可变信息。
     *
     * @param deviceId 设备标识
     * @param request  设备修改请求
     * @return 更新后的设备
     */
    @PutMapping("/devices/{id}")
    @SaCheckPermission("device:device:update")
    public DeviceResponse updateDevice(
            @PathVariable("id") UUID deviceId,
            @Valid @RequestBody DeviceUpdateRequest request
    ) {
        return commandService.updateDevice(
                deviceId,
                request.name(),
                request.status(),
                request.version()
        );
    }

    /**
     * 归档设备。
     *
     * @param deviceId 设备标识
     * @param request  归档请求
     * @return 空响应
     */
    @PostMapping("/devices/{id}/archive")
    @SaCheckPermission("device:device:archive")
    public ResponseEntity<Void> archiveDevice(
            @PathVariable("id") UUID deviceId,
            @Valid @RequestBody ArchiveRequest request
    ) {
        commandService.archiveDevice(deviceId, request.version());
        return ResponseEntity.noContent().build();
    }

    private void validatePage(int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new MineException(PAGE_INVALID, Map.of("page", page, "size", size));
        }
    }

    /**
     * 归档请求。
     *
     * @param version 期望版本号
     */
    public record ArchiveRequest(@NotNull @Min(0) Long version) {
    }
}
