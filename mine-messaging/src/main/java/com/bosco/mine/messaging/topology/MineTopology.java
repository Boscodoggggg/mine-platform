package com.bosco.mine.messaging.topology;

/**
 * 集中声明设备接入和平台内部消息使用的稳定 RabbitMQ 拓扑名称。
 * 业务模块只能引用这些常量，不得在消费者中散落硬编码名称。
 */
public final class MineTopology {

    /** 设备 MQTT 上下行虚拟主机。 */
    public static final String DEVICE_VHOST = "/mine-device";

    /** 平台内部可靠消息虚拟主机。 */
    public static final String PLATFORM_VHOST = "/mine-platform";

    /** MQTT 插件使用的主题交换机。 */
    public static final String MQTT_EXCHANGE = "amq.topic";

    /** 设备上行原始报文队列。 */
    public static final String DEVICE_UPLINK_QUEUE = "mine.gateway.uplink.q";

    /** 设备命令业务 ACK 原始报文队列。 */
    public static final String DEVICE_COMMAND_ACK_QUEUE = "mine.gateway.command-ack.q";

    /** 设备侧不可重试原始报文死信交换机。 */
    public static final String DEVICE_DEAD_LETTER_EXCHANGE = "mine.device.dlx";

    /** 设备侧不可重试原始报文死信队列。 */
    public static final String DEVICE_DEAD_LETTER_QUEUE = "mine.device.dead-letter.q";

    /** MQTT 遥测上行主题绑定模式。 */
    public static final String DEVICE_UPLINK_ROUTING_PATTERN = "mine.v1.*.*.up.telemetry";

    /** MQTT 命令业务 ACK 上行主题绑定模式。 */
    public static final String DEVICE_COMMAND_ACK_ROUTING_PATTERN = "mine.v1.*.*.up.command-ack";

    /** 标准化遥测消息交换机。 */
    public static final String TELEMETRY_EXCHANGE = "mine.telemetry.x";

    /** 平台下行指令交换机。 */
    public static final String COMMAND_EXCHANGE = "mine.command.x";

    /** 动态配置发布交换机。 */
    public static final String CONFIG_EXCHANGE = "mine.config.x";

    /** 已持久化最新值的实时通知交换机。 */
    public static final String REALTIME_EXCHANGE = "mine.realtime.x";

    /** 实时遥测事件路由模式。 */
    public static final String REALTIME_TELEMETRY_ROUTING_PATTERN = "realtime.telemetry.#";

    /** 平台内部死信交换机。 */
    public static final String DEAD_LETTER_EXCHANGE = "mine.dlx";

    /** 遥测处理服务消费队列。 */
    public static final String TELEMETRY_QUEUE = "mine.telemetry.q";

    /** 接入服务下行指令消费队列。 */
    public static final String COMMAND_QUEUE = "mine.gateway.command.q";

    /** 管理服务命令状态消费队列。 */
    public static final String COMMAND_STATUS_QUEUE = "mine.server.command-status.q";

    /** 命令回复超时等待队列，消息到期后死信到状态路由。 */
    public static final String COMMAND_TIMEOUT_WAIT_QUEUE = "mine.command.timeout.wait.q";

    /** 动态配置消费队列。 */
    public static final String CONFIG_QUEUE = "mine.config.q";

    /** 人工处置和重放使用的死信队列。 */
    public static final String DEAD_LETTER_QUEUE = "mine.dead-letter.q";

    /** 遥测消息路由模式。 */
    public static final String TELEMETRY_ROUTING_PATTERN = "telemetry.#";

    /** 下行指令路由模式。 */
    public static final String COMMAND_DISPATCH_ROUTING_PATTERN = "command.dispatch.#";

    /** 命令状态路由模式。 */
    public static final String COMMAND_STATUS_ROUTING_PATTERN = "command.status.#";

    /** 配置发布路由模式。 */
    public static final String CONFIG_ROUTING_PATTERN = "config.#";

    private MineTopology() {
    }
}
