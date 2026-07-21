package com.bosco.mine.server.modules.mine.operation;

import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.server.modules.mine.model.MineSite;
import com.bosco.mine.server.modules.mine.service.MineScopeAuthorizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 在业务事务内认领生产运营动作幂等键并冻结首次成功响应。
 */
@Service
public class OperationActionIdempotencyService {

    private static final TypeReference<Object> OBJECT_TYPE = new TypeReference<>() { };
    private static final Pattern ACTION_NAME = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern AGGREGATE_NAME = Pattern.compile("[A-Z][A-Z0-9_]{0,31}");
    private static final ErrorCode CONFLICT = new ErrorCode(
            "MINE-OPERATION-409-001", "幂等键已用于不同的生产运营请求");
    private static final ErrorCode INVALID = new ErrorCode(
            "MINE-OPERATION-422-001", "生产运营动作幂等参数不合法");

    private final OperationMapper mapper;
    private final MineScopeAuthorizer authorizer;
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator = IdGenerator.timeOrdered();

    /**
     * 创建生产运营动作幂等服务。
     *
     * @param mapper 运营控制面数据访问接口
     * @param authorizer 矿区作用域授权器
     * @param objectMapper JSON 映射器
     */
    public OperationActionIdempotencyService(
            OperationMapper mapper,
            MineScopeAuthorizer authorizer,
            ObjectMapper objectMapper
    ) {
        this.mapper = mapper;
        this.authorizer = authorizer;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行一次带持久幂等语义的矿区业务动作。
     *
     * <p>同一操作者在同一矿区使用相同键和规范化请求时返回首次响应快照；
     * 相同键对应不同动作、聚合或请求时固定返回冲突。</p>
     *
     * @param siteId 矿区标识
     * @param idempotencyKey 调用方幂等键
     * @param actionType 动作类型
     * @param aggregateType 聚合类型
     * @param aggregateId 聚合标识
     * @param request 参与摘要的规范化请求
     * @param responseType 响应类型
     * @param action 首次请求执行的业务动作
     * @param <T> 响应类型
     * @return 首次动作响应或其冻结重放
     */
    @Transactional
    public <T> T execute(
            UUID siteId,
            String idempotencyKey,
            String actionType,
            String aggregateType,
            UUID aggregateId,
            Object request,
            Class<T> responseType,
            Supplier<T> action
    ) {
        MineSite site = authorizer.requireSite(siteId);
        UUID operatorId = authorizer.currentUserId();
        String key = required(idempotencyKey, 128, null);
        String normalizedAction = required(actionType, 64, ACTION_NAME);
        String normalizedAggregate = required(aggregateType, 32, AGGREGATE_NAME);
        if (aggregateId == null || request == null || responseType == null || action == null) {
            throw invalid();
        }
        String requestHash = sha256(canonicalJson(request));
        UUID recordId = idGenerator.next();
        int claimed = mapper.claimOperationAction(
                recordId, site.organizationId(), site.id(), operatorId, key,
                normalizedAction, normalizedAggregate, aggregateId, requestHash);
        if (claimed == 0) {
            OperationMapper.OperationActionIdempotencyRow existing =
                    mapper.findOperationAction(
                                    site.organizationId(), site.id(), operatorId, key)
                            .orElseThrow(this::conflict);
            verifyReplay(existing, normalizedAction, normalizedAggregate, aggregateId, requestHash);
            if (existing.responseSnapshotJson() == null || existing.completedAt() == null) {
                throw conflict();
            }
            return read(existing.responseSnapshotJson(), responseType);
        }

        T response = action.get();
        if (response == null) {
            throw invalid();
        }
        String responseJson = write(response);
        if (mapper.completeOperationAction(recordId, responseJson) != 1) {
            throw new IllegalStateException("生产运营动作幂等响应冻结失败");
        }
        return response;
    }

    /**
     * 执行一次创建新聚合的持久幂等动作，并为首次请求分配 UUIDv7 聚合标识。
     *
     * <p>重放请求没有尚未创建的聚合标识可供比较，因此仅校验动作、聚合类型和
     * 规范化请求摘要；首次分配的聚合标识随冻结响应稳定返回。</p>
     *
     * @param siteId 矿区标识
     * @param idempotencyKey 调用方幂等键
     * @param actionType 动作类型
     * @param aggregateType 聚合类型
     * @param request 参与摘要的规范化请求
     * @param responseType 响应类型
     * @param action 使用首次分配聚合标识执行的创建动作
     * @param <T> 响应类型
     * @return 首次动作响应或其冻结重放
     */
    @Transactional
    public <T> T executeCreate(
            UUID siteId,
            String idempotencyKey,
            String actionType,
            String aggregateType,
            Object request,
            Class<T> responseType,
            Function<UUID, T> action
    ) {
        MineSite site = authorizer.requireSite(siteId);
        UUID operatorId = authorizer.currentUserId();
        String key = required(idempotencyKey, 128, null);
        String normalizedAction = required(actionType, 64, ACTION_NAME);
        String normalizedAggregate = required(aggregateType, 32, AGGREGATE_NAME);
        if (request == null || responseType == null || action == null) {
            throw invalid();
        }
        String requestHash = sha256(canonicalJson(request));
        UUID aggregateId = idGenerator.next();
        UUID recordId = idGenerator.next();
        int claimed = mapper.claimOperationAction(
                recordId, site.organizationId(), site.id(), operatorId, key,
                normalizedAction, normalizedAggregate, aggregateId, requestHash);
        if (claimed == 0) {
            OperationMapper.OperationActionIdempotencyRow existing =
                    mapper.findOperationAction(
                                    site.organizationId(), site.id(), operatorId, key)
                            .orElseThrow(this::conflict);
            verifyCreateReplay(
                    existing, normalizedAction, normalizedAggregate, requestHash);
            if (existing.responseSnapshotJson() == null || existing.completedAt() == null) {
                throw conflict();
            }
            return read(existing.responseSnapshotJson(), responseType);
        }

        T response = action.apply(aggregateId);
        if (response == null) {
            throw invalid();
        }
        String responseJson = write(response);
        if (mapper.completeOperationAction(recordId, responseJson) != 1) {
            throw new IllegalStateException("生产运营创建动作幂等响应冻结失败");
        }
        return response;
    }

    private void verifyReplay(
            OperationMapper.OperationActionIdempotencyRow existing,
            String actionType,
            String aggregateType,
            UUID aggregateId,
            String requestHash
    ) {
        if (!actionType.equals(existing.actionType())
                || !aggregateType.equals(existing.aggregateType())
                || !aggregateId.equals(existing.aggregateId())
                || !requestHash.equals(existing.requestHash())) {
            throw conflict();
        }
    }

    private void verifyCreateReplay(
            OperationMapper.OperationActionIdempotencyRow existing,
            String actionType,
            String aggregateType,
            String requestHash
    ) {
        if (!actionType.equals(existing.actionType())
                || !aggregateType.equals(existing.aggregateType())
                || !requestHash.equals(existing.requestHash())) {
            throw conflict();
        }
    }

    private String canonicalJson(Object request) {
        try {
            Object value = objectMapper.readValue(objectMapper.writeValueAsString(request), OBJECT_TYPE);
            return objectMapper.writeValueAsString(sort(value));
        } catch (JacksonException exception) {
            MineException invalid = invalid();
            invalid.addSuppressed(exception);
            throw invalid;
        }
    }

    private Object sort(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), sort(item)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> sorted = new ArrayList<>(list.size());
            list.forEach(item -> sorted.add(sort(item)));
            return List.copyOf(sorted);
        }
        return value;
    }

    private String required(String value, int maxLength, Pattern pattern) {
        if (value == null || value.isBlank()) {
            throw invalid();
        }
        String normalized = value.strip();
        if (pattern != null) {
            normalized = normalized.toUpperCase(Locale.ROOT);
        }
        if (normalized.length() > maxLength
                || (pattern != null && !pattern.matcher(normalized).matches())) {
            throw invalid();
        }
        return normalized;
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("生产运营动作响应无法冻结", exception);
        }
    }

    private <T> T read(String value, Class<T> responseType) {
        try {
            return objectMapper.readValue(value, responseType);
        } catch (JacksonException exception) {
            throw new IllegalStateException("生产运营动作幂等响应无法解析", exception);
        }
    }

    private String sha256(String value) {
        try {
            return "sha256:" + java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256 摘要算法", exception);
        }
    }

    private MineException conflict() {
        return new MineException(CONFLICT, Map.of());
    }

    private MineException invalid() {
        return new MineException(INVALID, Map.of());
    }
}
