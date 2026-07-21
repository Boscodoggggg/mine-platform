package com.bosco.mine.server.modules.mine.spatial;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.modules.mine.spatial.dto.SpatialEventDetailResponse;
import com.bosco.mine.server.modules.mine.spatial.dto.SpatialEventPageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * 提供受矿区作用域和独立事件权限保护的空间事件查询接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1/mine/sites/{siteId}/spatial-events")
@ConditionalOnProperty(prefix = "mine.telemetry-query", name = "enabled", havingValue = "true")
public class SpatialEventController {

    private final SpatialEventQueryService queryService;

    /**
     * 创建空间事件控制器。
     *
     * @param queryService 空间事件查询服务
     */
    public SpatialEventController(SpatialEventQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * 查询最多三十一天内的一页空间事件摘要。
     *
     * @param siteId 矿区标识
     * @param from 开始时间，包含
     * @param to 结束时间，包含
     * @param watermark 首页返回的固定水位
     * @param cursor 下一页不透明游标
     * @param zoneType 区域类型
     * @param zoneId 稳定区域标识
     * @param subjectType 对象类型
     * @param subjectId 对象标识
     * @param eventType 事件类型
     * @param pageSize 每页数量
     * @return 固定水位分页结果
     */
    @GetMapping
    @SaCheckPermission("mine:spatial:event:view")
    public SpatialEventPageResponse findEvents(
            @PathVariable UUID siteId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) @Min(0) Long watermark,
            @RequestParam(required = false) @Size(max = 4096) String cursor,
            @RequestParam(required = false)
            @Pattern(regexp = "WAYPOINT|GEOFENCE") String zoneType,
            @RequestParam(required = false) UUID zoneId,
            @RequestParam(required = false)
            @Pattern(regexp = "TAG|VEHICLE|PERSON") String subjectType,
            @RequestParam(required = false) UUID subjectId,
            @RequestParam(required = false)
            @Pattern(regexp = "ENTER|EXIT|DWELL") String eventType,
            @RequestParam(defaultValue = "100") @Min(1) @Max(200) int pageSize
    ) {
        return queryService.findEvents(
                siteId, from, to, watermark, cursor, zoneType, zoneId,
                subjectType, subjectId, eventType, pageSize);
    }

    /**
     * 查询单条空间事件及其判定证据。
     *
     * @param siteId 矿区标识
     * @param eventId 空间事件标识
     * @return 空间事件详情
     */
    @GetMapping("/{eventId}")
    @SaCheckPermission("mine:spatial:event:view")
    public SpatialEventDetailResponse findEvent(
            @PathVariable UUID siteId,
            @PathVariable UUID eventId
    ) {
        return queryService.findEvent(siteId, eventId);
    }
}
