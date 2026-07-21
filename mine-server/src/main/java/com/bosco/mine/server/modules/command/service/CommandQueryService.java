package com.bosco.mine.server.modules.command.service;

import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.bootstrap.web.PageResponse;
import com.bosco.mine.server.modules.command.dto.CommandResponse;
import com.bosco.mine.server.modules.command.dto.CommandTargetResponse;
import com.bosco.mine.server.modules.command.repository.CommandMapper;
import com.bosco.mine.server.modules.command.repository.StoredCommand;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 提供命令实例和状态时间线只读查询。
 */
@Service
public class CommandQueryService {
    private static final ErrorCode COMMAND_NOT_FOUND =
            new ErrorCode("COMMAND-404-001", "命令不存在");
    private static final ErrorCode PAGE_INVALID =
            new ErrorCode("COMMAND-400-001", "分页参数不合法");

    private final CommandMapper mapper;
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    /**
     * 创建命令查询服务。
     *
     * @param mapper 命令数据访问接口
     */
    public CommandQueryService(CommandMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 分页查询命令。
     *
     * @param deviceId 设备筛选
     * @param status 状态筛选
     * @param page 页码
     * @param size 每页数量
     * @return 命令分页
     */
    @Transactional(readOnly = true)
    public PageResponse<CommandResponse> findCommands(UUID deviceId, String status, int page, int size) {
        return findCommands(deviceId, status, null, page, size);
    }

    /**
     * 按设备、状态和模板分页查询命令。
     */
    @Transactional(readOnly = true)
    public PageResponse<CommandResponse> findCommands(
            UUID deviceId,
            String status,
            String templateKey,
            int page,
            int size
    ) {
        String normalizedStatus = status == null || status.isBlank()
                ? null : status.strip().toUpperCase(Locale.ROOT);
        String normalizedTemplate = templateKey == null || templateKey.isBlank() ? null : templateKey.strip();
        int offset = offsetOf(page, size);
        return new PageResponse<>(
                mapper.findCommands(deviceId, normalizedStatus, normalizedTemplate, offset, size)
                        .stream().map(this::toResponse).toList(),
                page, size, mapper.countCommands(deviceId, normalizedStatus, normalizedTemplate)
        );
    }

    /**
     * 分页查询可接收低风险命令的启用设备。
     *
     * @param keyword 设备编码或名称关键词
     * @param page 页码
     * @param size 每页数量
     * @return 命令目标设备分页
     */
    @Transactional(readOnly = true)
    public PageResponse<CommandTargetResponse> findTargets(String keyword, int page, int size) {
        String normalizedKeyword = keyword == null || keyword.isBlank() ? null : keyword.strip();
        int offset = offsetOf(page, size);
        return new PageResponse<>(
                mapper.findEnabledTargets(normalizedKeyword, offset, size),
                page, size, mapper.countEnabledTargets(normalizedKeyword)
        );
    }

    /**
     * 查询单条命令。
     *
     * @param commandId 命令标识
     * @return 命令响应
     */
    @Transactional(readOnly = true)
    public CommandResponse findById(UUID commandId) {
        return mapper.findById(commandId)
                .map(this::toResponse)
                .orElseThrow(() -> new MineException(COMMAND_NOT_FOUND, Map.of("commandId", commandId)));
    }

    /**
     * 将数据库命令快照转换为接口响应。
     *
     * @param stored 数据库命令快照
     * @return 命令响应
     */
    private CommandResponse toResponse(StoredCommand stored) {
        return new CommandResponse(
                stored.id(), stored.deviceId(), stored.templateKey(), stored.templateVersion(),
                readMap(stored.parametersJson()), stored.reason(), stored.workOrderNo(),
                stored.cancelledBy(), stored.cancelReason(), stored.dispatchSource(),
                stored.payloadFormat(), stored.rawPayloadHex(), stored.payloadSha256(),
                stored.replyExpected(), stored.riskLevel(), stored.status(),
                stored.ttlSeconds(), stored.timeoutSeconds(), stored.idempotencyKey(),
                stored.createdAt(), stored.updatedAt(), stored.queuedAt(), stored.sentAt(),
                stored.deviceReceivedAt(), stored.completedAt(), stored.expiresAt(),
                stored.lastErrorCode(), stored.replyJson() == null ? null : readMap(stored.replyJson()),
                stored.version()
        );
    }

    /**
     * 读取数据库 JSON 对象。
     *
     * @param value JSON 文本
     * @return 键值对象
     */
    private Map<String, Object> readMap(String value) {
        try {
            return jsonMapper.readValue(value, new TypeReference<>() { });
        } catch (JacksonException exception) {
            throw new IllegalStateException("数据库命令 JSON 无法解析", exception);
        }
    }

    private int offsetOf(int page, int size) {
        if (page < 1 || page > 1_000_000 || size < 1 || size > 100) {
            throw new MineException(PAGE_INVALID, Map.of());
        }
        try {
            return Math.multiplyExact(page - 1, size);
        } catch (ArithmeticException exception) {
            throw new MineException(PAGE_INVALID, Map.of());
        }
    }
}
