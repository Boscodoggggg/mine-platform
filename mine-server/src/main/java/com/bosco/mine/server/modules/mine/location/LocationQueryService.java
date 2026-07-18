package com.bosco.mine.server.modules.mine.location;

import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.modules.mine.dto.CurrentLocationResponse;
import com.bosco.mine.server.modules.mine.dto.LocationTrajectoryResponse;
import com.bosco.mine.server.modules.mine.location.LocationQueryRepository.CurrentResult;
import com.bosco.mine.server.modules.mine.location.LocationQueryRepository.SubjectType;
import com.bosco.mine.server.modules.mine.location.LocationQueryRepository.TrajectoryQuery;
import com.bosco.mine.server.modules.mine.location.LocationQueryRepository.TrajectorySlice;
import com.bosco.mine.server.modules.mine.service.MineScopeAuthorizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 强制矿区作用域、时间窗、固定水位和服务端轨迹抽稀的位置查询服务。
 */
@Service
@ConditionalOnProperty(prefix = "mine.telemetry-query", name = "enabled", havingValue = "true")
public class LocationQueryService {

    private static final ErrorCode RANGE_INVALID =
            new ErrorCode("MINE-LOCATION-400-001", "位置轨迹时间范围不合法");
    private static final ErrorCode SUBJECT_INVALID =
            new ErrorCode("MINE-LOCATION-400-002", "位置轨迹对象不合法");
    private static final ErrorCode PAGE_INVALID =
            new ErrorCode("MINE-LOCATION-400-003", "位置轨迹游标或水位不合法");
    private static final ErrorCode QUERY_UNAVAILABLE =
            new ErrorCode("MINE-LOCATION-503-001", "位置查询服务暂时不可用");
    private static final Duration MAX_RANGE = Duration.ofHours(24);
    private static final int MAX_CURRENT = 10_000;
    private static final int MAX_OUTPUT_POINTS = 5_000;

    private final LocationQueryRepository repository;
    private final MineScopeAuthorizer authorizer;

    /**
     * 创建位置查询服务。
     *
     * @param repository 位置只读查询仓储
     * @param authorizer 矿区数据作用域授权器
     */
    public LocationQueryService(
            LocationQueryRepository repository,
            MineScopeAuthorizer authorizer
    ) {
        this.repository = Objects.requireNonNull(repository, "位置查询仓储不能为空");
        this.authorizer = Objects.requireNonNull(authorizer, "矿区授权器不能为空");
    }

    /**
     * 查询一个授权矿区的当前位置快照。
     *
     * @param siteId 矿区标识
     * @param limit 最大返回数量
     * @return 当前位置快照
     */
    public CurrentLocationResponse findCurrent(UUID siteId, int limit) {
        authorizer.requireSite(siteId);
        return findCurrentAuthorized(siteId, limit);
    }

    /**
     * 使用显式用户身份查询一个授权矿区的当前位置，供 WebSocket 请求线程复核作用域。
     *
     * @param userId 用户标识
     * @param siteId 矿区标识
     * @param limit 最大返回数量
     * @return 当前位置快照
     */
    public CurrentLocationResponse findCurrentForUser(UUID userId, UUID siteId, int limit) {
        authorizer.requireSiteForUser(userId, siteId);
        return findCurrentAuthorized(siteId, limit);
    }

    /**
     * 查询单个标签、车辆或人员在二十四小时内的一页稳定轨迹。
     *
     * @param siteId 矿区标识
     * @param subjectType 对象类型
     * @param subjectId 对象标识
     * @param from 开始时间，包含
     * @param to 结束时间，不包含
     * @param watermark 固定摄取水位，首页可为空
     * @param cursor 绑定本次查询的下一页游标
     * @param maxPoints 最大输出点数
     * @return 有界轨迹响应
     */
    public LocationTrajectoryResponse findTrajectory(
            UUID siteId,
            String subjectType,
            UUID subjectId,
            Instant from,
            Instant to,
            Long watermark,
            String cursor,
            int maxPoints
    ) {
        authorizer.requireSite(siteId);
        SubjectType resolvedType = subjectType(subjectType);
        validateRange(from, to);
        if (subjectId == null || maxPoints < 2 || maxPoints > MAX_OUTPUT_POINTS) {
            throw new MineException(SUBJECT_INVALID, Map.of());
        }
        if (cursor != null && watermark == null) {
            throw new MineException(PAGE_INVALID, Map.of("required", "watermark"));
        }
        try {
            long current = repository.currentWatermark(siteId);
            long resolvedWatermark = watermark == null ? current : watermark;
            if (resolvedWatermark < 0L || resolvedWatermark > current) {
                throw new MineException(PAGE_INVALID, Map.of(
                        "watermark", resolvedWatermark, "currentWatermark", current));
            }
            CursorState state = cursor == null ? null : decodeCursor(
                    cursor, siteId, resolvedType, subjectId, from, to, resolvedWatermark);
            TrajectorySlice slice = repository.findTrajectory(new TrajectoryQuery(
                    siteId, resolvedType, subjectId, from, to, resolvedWatermark,
                    state == null ? null : state.occurredAt(),
                    state == null ? null : state.ingestSequence()));
            List<LocationTrajectoryResponse.Point> sampled = sample(slice.points(), maxPoints);
            String nextCursor = slice.hasMore()
                    ? encodeCursor(siteId, resolvedType, subjectId, from, to,
                    resolvedWatermark, slice.lastOccurredAt(), slice.lastIngestSequence())
                    : null;
            return new LocationTrajectoryResponse(
                    siteId, resolvedType.name(), subjectId, from, to, resolvedWatermark,
                    nextCursor, sampled.size() < slice.points().size(),
                    slice.points().size(), sampled);
        } catch (MineException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MineException(QUERY_UNAVAILABLE, Map.of("siteId", siteId));
        }
    }

    private CurrentLocationResponse findCurrentAuthorized(UUID siteId, int limit) {
        if (limit < 1 || limit > MAX_CURRENT) {
            throw new MineException(SUBJECT_INVALID, Map.of(
                    "limit", limit, "maximum", MAX_CURRENT));
        }
        try {
            CurrentResult result = repository.findCurrent(siteId, limit);
            return new CurrentLocationResponse(
                    siteId, result.watermark(), result.source(), result.truncated(),
                    result.events().stream().map(CurrentLocationResponse.Item::from).toList());
        } catch (RuntimeException exception) {
            throw new MineException(QUERY_UNAVAILABLE, Map.of("siteId", siteId));
        }
    }

    private SubjectType subjectType(String value) {
        try {
            return SubjectType.valueOf(value == null ? "" : value);
        } catch (IllegalArgumentException exception) {
            throw new MineException(SUBJECT_INVALID, Map.of(
                    "subjectType", String.valueOf(value)));
        }
    }

    private void validateRange(Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)
                || Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw new MineException(RANGE_INVALID, Map.of("maximum", MAX_RANGE.toString()));
        }
    }

    private List<LocationTrajectoryResponse.Point> sample(
            List<LocationTrajectoryResponse.Point> points,
            int threshold
    ) {
        if (points.size() <= threshold) {
            return points;
        }
        if (threshold == 2) {
            return List.of(points.getFirst(), points.getLast());
        }
        List<LocationTrajectoryResponse.Point> sampled = new ArrayList<>(threshold);
        sampled.add(points.getFirst());
        double bucketWidth = (double) (points.size() - 2) / (threshold - 2);
        int anchor = 0;
        for (int index = 0; index < threshold - 2; index++) {
            int averageStart = (int) Math.floor((index + 1) * bucketWidth) + 1;
            int averageEnd = Math.min(
                    (int) Math.floor((index + 2) * bucketWidth) + 1, points.size());
            if (averageStart >= averageEnd) {
                averageStart = points.size() - 1;
                averageEnd = points.size();
            }
            double averageX = 0D;
            double averageY = 0D;
            for (int item = averageStart; item < averageEnd; item++) {
                averageX += points.get(item).localX();
                averageY += points.get(item).localY();
            }
            averageX /= averageEnd - averageStart;
            averageY /= averageEnd - averageStart;

            int rangeStart = (int) Math.floor(index * bucketWidth) + 1;
            int rangeEnd = Math.min(
                    (int) Math.floor((index + 1) * bucketWidth) + 1,
                    points.size() - 1);
            LocationTrajectoryResponse.Point anchorPoint = points.get(anchor);
            double largestArea = -1D;
            int selected = rangeStart;
            for (int item = rangeStart; item < rangeEnd; item++) {
                LocationTrajectoryResponse.Point candidate = points.get(item);
                double area = Math.abs(
                        (anchorPoint.localX() - averageX)
                                * (candidate.localY() - anchorPoint.localY())
                                - (anchorPoint.localX() - candidate.localX())
                                * (averageY - anchorPoint.localY()));
                if (area > largestArea) {
                    largestArea = area;
                    selected = item;
                }
            }
            sampled.add(points.get(selected));
            anchor = selected;
        }
        sampled.add(points.getLast());
        return List.copyOf(sampled);
    }

    private String encodeCursor(
            UUID siteId,
            SubjectType type,
            UUID subjectId,
            Instant from,
            Instant to,
            long watermark,
            Instant occurredAt,
            Long ingestSequence
    ) {
        String raw = String.join("|",
                "v1", siteId.toString(), type.name(), subjectId.toString(),
                from.toString(), to.toString(), Long.toString(watermark),
                occurredAt.toString(), ingestSequence.toString());
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private CursorState decodeCursor(
            String cursor,
            UUID siteId,
            SubjectType type,
            UUID subjectId,
            Instant from,
            Instant to,
            long watermark
    ) {
        try {
            String raw = new String(
                    Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] fields = raw.split("\\|", -1);
            if (fields.length != 9
                    || !"v1".equals(fields[0])
                    || !siteId.equals(UUID.fromString(fields[1]))
                    || type != SubjectType.valueOf(fields[2])
                    || !subjectId.equals(UUID.fromString(fields[3]))
                    || !from.equals(Instant.parse(fields[4]))
                    || !to.equals(Instant.parse(fields[5]))
                    || watermark != Long.parseLong(fields[6])) {
                throw new IllegalArgumentException("轨迹游标与查询不一致");
            }
            Instant occurredAt = Instant.parse(fields[7]);
            long ingestSequence = Long.parseLong(fields[8]);
            if (ingestSequence < 1L) {
                throw new IllegalArgumentException("轨迹游标序号不合法");
            }
            return new CursorState(occurredAt, ingestSequence);
        } catch (IllegalArgumentException exception) {
            throw new MineException(PAGE_INVALID, Map.of());
        }
    }

    private record CursorState(Instant occurredAt, long ingestSequence) {
    }
}
