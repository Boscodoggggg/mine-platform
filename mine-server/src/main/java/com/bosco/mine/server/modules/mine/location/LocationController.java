package com.bosco.mine.server.modules.mine.location;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.modules.mine.dto.CurrentLocationResponse;
import com.bosco.mine.server.modules.mine.dto.LocationTrajectoryResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
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
 * 提供受矿区作用域保护的当前位置和有界历史轨迹查询接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1/mine/sites/{siteId}/locations")
@ConditionalOnProperty(prefix = "mine.telemetry-query", name = "enabled", havingValue = "true")
public class LocationController {

    private final LocationQueryService queryService;

    /**
     * 创建位置查询控制器。
     *
     * @param queryService 位置查询服务
     */
    public LocationController(LocationQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * 查询矿区当前位置快照。
     *
     * @param siteId 矿区标识
     * @param limit 最大返回数量
     * @return 当前位置快照
     */
    @GetMapping("/current")
    @SaCheckPermission("mine:location:view")
    public CurrentLocationResponse findCurrent(
            @PathVariable UUID siteId,
            @RequestParam(defaultValue = "5000") @Min(1) @Max(10_000) int limit
    ) {
        return queryService.findCurrent(siteId, limit);
    }

    /**
     * 查询标签、车辆或人员在最多二十四小时内的稳定分页轨迹。
     *
     * @param siteId 矿区标识
     * @param subjectType 对象类型
     * @param subjectId 对象标识
     * @param from 开始时间，包含
     * @param to 结束时间，不包含
     * @param watermark 首页返回的固定水位，后续页必传
     * @param cursor 下一页不透明游标
     * @param maxPoints 每页最大输出点数
     * @return 有界轨迹响应
     */
    @GetMapping("/trajectory")
    @SaCheckPermission("mine:location:view")
    public LocationTrajectoryResponse findTrajectory(
            @PathVariable UUID siteId,
            @RequestParam @Pattern(regexp = "TAG|VEHICLE|PERSON") String subjectType,
            @RequestParam UUID subjectId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) @Min(0) Long watermark,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "5000") @Min(2) @Max(5_000) int maxPoints
    ) {
        return queryService.findTrajectory(
                siteId, subjectType, subjectId, from, to, watermark, cursor, maxPoints);
    }
}
