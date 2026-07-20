package com.bosco.mine.server.modules.mine.scene;

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
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 在场景业务事务内认领用户幂等键并冻结首次成功响应。
 */
@Service
public class SceneActionIdempotencyService {

    private static final TypeReference<Object> OBJECT_TYPE = new TypeReference<>() { };
    private static final Pattern ACTION = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern AGGREGATE = Pattern.compile("[A-Z][A-Z0-9_]{0,31}");
    private static final ErrorCode CONFLICT =
            new ErrorCode("MINE-SCENE-409-005", "幂等键已用于不同的场景请求");
    private static final ErrorCode INVALID =
            new ErrorCode("MINE-SCENE-422-001", "场景动作幂等参数不合法");

    private final SceneMapper mapper;
    private final MineScopeAuthorizer authorizer;
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator = IdGenerator.timeOrdered();

    /**
     * 创建场景动作幂等服务。
     *
     * @param mapper 场景数据访问接口
     * @param authorizer 矿区作用域授权器
     * @param objectMapper JSON 映射器
     */
    public SceneActionIdempotencyService(
            SceneMapper mapper,
            MineScopeAuthorizer authorizer,
            ObjectMapper objectMapper
    ) {
        this.mapper = mapper;
        this.authorizer = authorizer;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行一次带持久幂等语义的场景动作。
     *
     * @param siteId 矿区标识
     * @param idempotencyKey 调用方幂等键
     * @param actionType 动作类型
     * @param aggregateType 聚合类型
     * @param aggregateId 聚合标识
     * @param request 参与摘要的规范化请求
     * @param responseType 响应类型
     * @param action 首次请求执行业务动作
     * @param <T> 响应类型
     * @return 首次响应或冻结响应重放
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
        String normalizedAction = required(actionType, 64, ACTION);
        String normalizedAggregate = required(aggregateType, 32, AGGREGATE);
        if (aggregateId == null || request == null || responseType == null || action == null) {
            throw invalid();
        }
        String requestHash = sha256(canonicalJson(request));
        UUID recordId = idGenerator.next();
        int claimed = mapper.claimSceneAction(
                recordId, site.organizationId(), site.id(), operatorId, key,
                normalizedAction, normalizedAggregate, aggregateId, requestHash);
        if (claimed == 0) {
            SceneMapper.SceneActionRecord existing = mapper.findSceneAction(
                            site.organizationId(), site.id(), operatorId, key)
                    .orElseThrow(this::conflict);
            if (!normalizedAction.equals(existing.actionType())
                    || !normalizedAggregate.equals(existing.aggregateType())
                    || !aggregateId.equals(existing.aggregateId())
                    || !requestHash.equals(existing.requestHash())
                    || existing.responseSnapshotJson() == null
                    || existing.completedAt() == null) {
                throw conflict();
            }
            return read(existing.responseSnapshotJson(), responseType);
        }
        T response = action.get();
        if (response == null) {
            throw invalid();
        }
        if (mapper.completeSceneAction(recordId, write(response)) != 1) {
            throw new IllegalStateException("场景动作幂等响应冻结失败");
        }
        return response;
    }

    private String canonicalJson(Object request) {
        try {
            Object value = objectMapper.readValue(
                    objectMapper.writeValueAsString(request), OBJECT_TYPE);
            return objectMapper.writeValueAsString(sort(value));
        } catch (JacksonException exception) {
            MineException failure = invalid();
            failure.addSuppressed(exception);
            throw failure;
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

    private String required(String value, int maximumLength, Pattern pattern) {
        if (value == null || value.isBlank()) {
            throw invalid();
        }
        String normalized = value.strip();
        if (pattern != null) {
            normalized = normalized.toUpperCase(Locale.ROOT);
        }
        if (normalized.length() > maximumLength
                || (pattern != null && !pattern.matcher(normalized).matches())) {
            throw invalid();
        }
        return normalized;
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("场景动作响应无法冻结", exception);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JacksonException exception) {
            throw new IllegalStateException("场景动作幂等响应无法解析", exception);
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
