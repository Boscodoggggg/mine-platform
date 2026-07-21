package com.bosco.mine.server.modules.command.model;

/**
 * 命令的安全风险级别。
 */
public enum CommandRiskLevel {
    /** 只读或无设备运行风险的命令。 */
    LOW,
    /** 可修改设备运行参数的命令。 */
    MEDIUM,
    /** 可影响生产安全或设备动作的命令。 */
    HIGH
}
