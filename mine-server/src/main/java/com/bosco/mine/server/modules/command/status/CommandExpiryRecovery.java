package com.bosco.mine.server.modules.command.status;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.time.Clock;
import java.util.Objects;

/**
 * 在管理服务启动时修复历史版本遗留的过期排队命令。
 */
public final class CommandExpiryRecovery implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(CommandExpiryRecovery.class);

    private final CommandStatusRepository repository;
    private final Clock clock;

    /**
     * 创建命令过期恢复器。
     *
     * @param repository 命令状态仓储
     * @param clock 系统时钟
     */
    public CommandExpiryRecovery(CommandStatusRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "命令状态仓储不能为空");
        this.clock = Objects.requireNonNull(clock, "系统时钟不能为空");
    }

    /**
     * 修复当前所有已超过 TTL 的排队命令。
     *
     * @return 修复的命令数量
     */
    public int recover() {
        int recovered = repository.expireQueuedCommands(clock.instant());
        if (recovered > 0) {
            LOGGER.info("已修复历史过期排队命令: count={}", recovered);
        }
        return recovered;
    }

    /**
     * 在应用启动完成后执行一次恢复。
     *
     * @param args 应用启动参数
     */
    @Override
    public void run(ApplicationArguments args) {
        recover();
    }
}
