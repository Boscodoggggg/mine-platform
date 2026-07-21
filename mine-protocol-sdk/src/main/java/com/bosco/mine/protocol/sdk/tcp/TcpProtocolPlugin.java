package com.bosco.mine.protocol.sdk.tcp;

/**
 * 第三方 TCP 二进制协议插件的稳定入口。
 *
 * <p>实现类必须提供公开无参构造器并通过 {@link java.util.ServiceLoader} 注册。每个连接都由
 * {@link #openSession()} 创建独立会话，禁止在插件实例中保存跨连接可变状态。</p>
 */
public interface TcpProtocolPlugin {

    /** 当前平台支持的插件 API 主版本。 */
    int API_MAJOR_VERSION = 1;

    /**
     * @return 全局唯一、可用于配置的协议键
     */
    String protocolKey();

    /**
     * @return 实际写入统一消息和审计日志的协议版本
     */
    String protocolVersion();

    /**
     * @return 插件编译时依赖的 API 主版本
     */
    default int apiMajorVersion() {
        return API_MAJOR_VERSION;
    }

    /**
     * @return 受控长度字段拆帧规则
     */
    TcpFrameDefinition frameDefinition();

    /**
     * 为一个新 TCP 连接创建隔离的协议会话。
     *
     * @return 不与其他连接共享状态的会话
     */
    TcpProtocolSession openSession();
}
