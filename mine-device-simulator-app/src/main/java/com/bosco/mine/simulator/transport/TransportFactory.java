package com.bosco.mine.simulator.transport;

import com.bosco.mine.simulator.profile.SimulatorProfile;

import java.util.function.Consumer;

/**
 * 根据设备配置创建传输通道。
 */
public class TransportFactory {

    /**
     * 创建对应协议的传输通道。
     *
     * @param profile 设备配置
     * @param password 本次启动临时密码
     * @param logger 日志接收器
     * @return 新传输通道
     */
    public SimulatorTransport create(SimulatorProfile profile, char[] password, Consumer<String> logger) {
        return switch (profile.transportMode()) {
            case MQTT -> new MqttTransport(profile, password, logger);
            case TCP_CLIENT -> new TcpClientTransport(profile, logger);
            case TCP_SERVER -> new TcpServerTransport(profile, logger);
        };
    }
}
