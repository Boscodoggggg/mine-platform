package com.bosco.mine.server.modules.command.service;

import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.modules.command.dto.CommandTemplateResponse;

import java.util.List;
import java.util.Map;

/**
 * 定义当前正式发布且允许从管理端下发的低风险命令模板。
 *
 * <p>模板参数和回复白名单必须随版本在此处同时固化，避免界面声明、下发校验和回复脱敏发生漂移。</p>
 */
public final class PublishedCommandTemplates {
    /** 状态查询模板编码。 */
    public static final String QUERY_STATUS = "QUERY_STATUS";
    /** 修改设备遥测上报周期模板编码。 */
    public static final String SET_REPORT_INTERVAL = "SET_REPORT_INTERVAL";
    /** 请求设备立即上报位置模板编码。 */
    public static final String REQUEST_LOCATION = "REQUEST_LOCATION";

    private static final ErrorCode TEMPLATE_NOT_PUBLISHED =
            new ErrorCode("COMMAND-422-001", "命令模板未发布");
    private static final ErrorCode PARAMETERS_INVALID =
            new ErrorCode("COMMAND-422-003", "命令参数不符合模板约束");

    private PublishedCommandTemplates() {
    }

    /**
     * 返回管理端可见的已发布模板。
     *
     * @return 不可变模板列表
     */
    public static List<CommandTemplateResponse> publishedTemplates() {
        return List.of(
                new CommandTemplateResponse(
                        QUERY_STATUS, 1, "LOW", 300, 30, emptyObjectSchema(), false
                ),
                new CommandTemplateResponse(
                        SET_REPORT_INTERVAL, 1, "LOW", 600, 60,
                        Map.of(
                                "type", "object",
                                "additionalProperties", false,
                                "required", List.of("intervalSeconds"),
                                "properties", Map.of("intervalSeconds", Map.of(
                                        "type", "integer", "minimum", 30, "maximum", 3600,
                                        "titleKey", "mine.commands.parameter.intervalSeconds"
                                ))
                        ),
                        true
                ),
                new CommandTemplateResponse(
                        REQUEST_LOCATION, 1, "LOW", 300, 30, emptyObjectSchema(), false
                )
        );
    }

    /**
     * 校验模板是否发布以及参数是否符合当前固化版本。
     *
     * @param templateKey 模板编码
     * @param parameters 命令参数
     */
    public static void validateParameters(String templateKey, Map<String, Object> parameters) {
        if (parameters == null) throw invalid(templateKey);
        switch (templateKey) {
            case QUERY_STATUS, REQUEST_LOCATION -> {
                if (!parameters.isEmpty()) throw invalid(templateKey);
            }
            case SET_REPORT_INTERVAL -> validateInterval(templateKey, parameters);
            default -> throw new MineException(TEMPLATE_NOT_PUBLISHED, Map.of("templateKey", templateKey));
        }
    }

    /**
     * 判断模板是否强制填写现场下发原因。
     *
     * @param templateKey 模板编码
     * @return 是否必填原因
     */
    public static boolean requiresReason(String templateKey) {
        return SET_REPORT_INTERVAL.equals(templateKey);
    }

    /**
     * 按命令模板回复白名单提取允许持久化和展示的字段。
     *
     * <p>状态查询模板当前只公开布尔类型的在线状态，其余设备私有字段全部丢弃。</p>
     *
     * @param templateKey 命令模板编码
     * @param reply 原始设备回复
     * @return 已脱敏且有界的回复
     */
    public static Map<String, Object> sanitizeReply(String templateKey, Map<String, Object> reply) {
        if (reply == null) {
            return Map.of();
        }
        if (QUERY_STATUS.equals(templateKey)) {
            Object online = reply.get("online");
            return online instanceof Boolean ? Map.of("online", online) : Map.of();
        }
        if (SET_REPORT_INTERVAL.equals(templateKey)) {
            Object applied = reply.get("applied");
            return applied instanceof Boolean ? Map.of("applied", applied) : Map.of();
        }
        if (REQUEST_LOCATION.equals(templateKey)) {
            Object latitude = reply.get("latitude");
            Object longitude = reply.get("longitude");
            return latitude instanceof Number && longitude instanceof Number
                    ? Map.of("latitude", latitude, "longitude", longitude) : Map.of();
        }
        return Map.of();
    }

    private static Map<String, Object> emptyObjectSchema() {
        return Map.of("type", "object", "additionalProperties", false, "properties", Map.of());
    }

    private static void validateInterval(String templateKey, Map<String, Object> parameters) {
        if (!parameters.keySet().equals(java.util.Set.of("intervalSeconds"))) throw invalid(templateKey);
        Object value = parameters.get("intervalSeconds");
        if (!(value instanceof Number number)
                || number.doubleValue() != Math.rint(number.doubleValue())
                || number.longValue() < 30 || number.longValue() > 3600) {
            throw invalid(templateKey);
        }
    }

    private static MineException invalid(String templateKey) {
        return new MineException(PARAMETERS_INVALID, Map.of("templateKey", templateKey));
    }
}
