package com.bosco.mine.server.modules.device.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.modules.device.dto.DeviceLatestResponse;
import com.bosco.mine.server.modules.device.dto.TelemetryHistoryResponse;
import com.bosco.mine.server.modules.device.service.DeviceTelemetryQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 提供设备最新值和数值遥测历史只读接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1/devices/{id}")
@ConditionalOnProperty(prefix = "mine.telemetry-query", name = "enabled", havingValue = "true")
public class DeviceTelemetryController {

    private final DeviceTelemetryQueryService queryService;

    /**
     * 创建设备遥测查询控制器。
     *
     * @param queryService 设备遥测查询服务
     */
    public DeviceTelemetryController(DeviceTelemetryQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * 查询指定属性的设备最新值。
     *
     * @param deviceId 设备标识
     * @param propertyIds 属性标识集合，最多一百个
     * @return 设备最新值快照
     */
    @GetMapping("/latest")
    @SaCheckPermission("device:telemetry:view")
    public DeviceLatestResponse findLatest(
            @PathVariable("id") UUID deviceId,
            @RequestParam
            @NotEmpty
            @Size(max = 100)
            List<@Pattern(regexp = "[A-Za-z][A-Za-z0-9_.-]{0,127}") String> propertyIds
    ) {
        return queryService.findLatest(deviceId, propertyIds);
    }

    /**
     * 分页查询单个数值属性的原始或聚合历史。
     *
     * @param deviceId 设备标识
     * @param propertyId 属性标识
     * @param from 开始时间，包含
     * @param to 结束时间，不包含
     * @param aggregation 聚合方式
     * @param bucket 聚合时间桶
     * @param watermark 分页摄取水位；首页为空时由服务端生成，后续页原样传回
     * @param rollupWatermark 分页聚合水位；首页为空时由服务端生成，后续页原样传回
     * @param page 页码
     * @param size 每页数量
     * @return 历史分页结果
     */
    @GetMapping("/telemetry")
    @SaCheckPermission("device:telemetry:view")
    public TelemetryHistoryResponse findHistory(
            @PathVariable("id") UUID deviceId,
            @RequestParam @Pattern(regexp = "[A-Za-z][A-Za-z0-9_.-]{0,127}") String propertyId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "RAW")
            @Pattern(regexp = "RAW|AVG|MIN|MAX") String aggregation,
            @RequestParam(required = false) Duration bucket,
            @RequestParam(required = false) @Min(0) Long watermark,
            @RequestParam(required = false) @Min(0) Long rollupWatermark,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "100") @Min(1) @Max(1000) int size
    ) {
        return queryService.findHistory(
                deviceId, propertyId, from, to, aggregation, bucket,
                watermark, rollupWatermark, page, size
        );
    }
}
