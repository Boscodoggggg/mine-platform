package com.bosco.mine.server.modules.command.dto;

import java.util.UUID;

/**
 * 可接收低风险命令的启用设备摘要。
 *
 * @param id 设备标识
 * @param deviceCode 设备编码
 * @param name 设备名称
 */
public record CommandTargetResponse(UUID id, String deviceCode, String name) {
}
