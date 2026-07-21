package com.bosco.mine.server.modules.command.template;

import java.util.UUID;

/**
 * 命令模板可绑定的活动产品摘要。
 *
 * @param id 产品标识
 * @param productKey 产品编码
 * @param name 产品名称
 */
public record CommandTemplateProductResponse(UUID id, String productKey, String name) {
}
