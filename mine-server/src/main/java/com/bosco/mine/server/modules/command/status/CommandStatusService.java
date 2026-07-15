package com.bosco.mine.server.modules.command.status;

import com.bosco.mine.messaging.command.CommandStatusPayload;
import com.bosco.mine.server.modules.command.status.CommandStatusRepository.CommandStateUpdate;
import com.bosco.mine.server.modules.command.status.CommandStatusRepository.CommandStatusSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 以 Inbox 幂等和数据库行锁推进命令状态，容忍 SENT、ACK 与 TIMEOUT 乱序到达。
 */
@Service
public class CommandStatusService {
    private static final Set<String> TERMINAL_ACK = Set.of("SUCCEEDED", "REFUSED", "FAILED");
    private static final Set<String> TERMINAL = Set.of(
            "SUCCEEDED", "REFUSED", "FAILED", "EXPIRED", "TIMEOUT", "CANCELLED"
    );

    private final CommandStatusRepository repository;
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    /**
     * 创建命令状态服务。
     *
     * @param repository 命令状态仓储
     */
    public CommandStatusService(CommandStatusRepository repository) {
        this.repository = repository;
    }

    /**
     * 幂等应用一个命令状态事件。
     *
     * @param eventId 事件标识
     * @param payload 状态载荷
     * @return 状态实际发生变化为真，重复或迟到事件为假
     */
    @Transactional
    public boolean apply(UUID eventId, CommandStatusPayload payload) {
        Objects.requireNonNull(eventId, "事件标识不能为空");
        Objects.requireNonNull(payload, "状态载荷不能为空");
        if (!repository.recordInbox(eventId)) {
            return false;
        }
        CommandStatusSnapshot current = repository.lockById(payload.commandId())
                .orElseThrow(() -> new IllegalStateException("命令状态事件早于命令记录: " + payload.commandId()));
        if (!current.deviceId().equals(payload.deviceId())) {
            throw new InvalidCommandStatusMessageException("状态事件设备标识与命令不一致");
        }
        CommandStateUpdate update = decide(current, payload);
        if (update == null) {
            return false;
        }
        repository.update(payload.commandId(), update);
        return true;
    }

    private CommandStateUpdate decide(CommandStatusSnapshot current, CommandStatusPayload payload) {
        String source = current.status();
        String target = payload.status();
        if (TERMINAL.contains(source) || source.equals(target)) {
            return null;
        }
        if (TERMINAL_ACK.contains(target)) {
            if (payload.deviceReceivedAt() == null) {
                throw new InvalidCommandStatusMessageException("设备终态缺少 deviceReceivedAt");
            }
            if (Set.of("QUEUED", "SENT", "DEVICE_RECEIVED").contains(source)) {
                return new CommandStateUpdate(
                        target, payload.occurredAt(),
                        current.sentAt() == null ? payload.deviceReceivedAt() : null,
                        payload.deviceReceivedAt(), payload.occurredAt(),
                        writeReply(payload), payload.errorCode()
                );
            }
            return null;
        }
        return switch (source + "->" + target) {
            case "QUEUED->SENT" -> new CommandStateUpdate(
                    "SENT", payload.occurredAt(), payload.occurredAt(),
                    null, null, "{}", null
            );
            case "SENT->DEVICE_RECEIVED" -> new CommandStateUpdate(
                    "DEVICE_RECEIVED", payload.occurredAt(), null,
                    payload.deviceReceivedAt() == null ? payload.occurredAt() : payload.deviceReceivedAt(),
                    null, writeReply(payload), payload.errorCode()
            );
            case "QUEUED->EXPIRED" -> new CommandStateUpdate(
                    "EXPIRED", payload.occurredAt(), null, null,
                    payload.occurredAt(), writeReply(payload), payload.errorCode()
            );
            case "SENT->TIMEOUT", "DEVICE_RECEIVED->TIMEOUT" -> new CommandStateUpdate(
                    "TIMEOUT", payload.occurredAt(), null, null,
                    payload.occurredAt(), writeReply(payload), payload.errorCode()
            );
            default -> null;
        };
    }

    private String writeReply(CommandStatusPayload payload) {
        try {
            return jsonMapper.writeValueAsString(payload.reply());
        } catch (JacksonException exception) {
            throw new InvalidCommandStatusMessageException("命令回复无法序列化", exception);
        }
    }
}
