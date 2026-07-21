package com.bosco.mine.simulator.transport;

import com.bosco.mine.simulator.profile.SimulatorProfile;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.io.IOException;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * MQTT 与 MQTTS 设备上报传输实现。
 */
public final class MqttTransport implements SimulatorTransport {

    private final SimulatorProfile profile;
    private final char[] password;
    private final Consumer<String> logger;
    private volatile TransportState state = TransportState.DISCONNECTED;
    private MqttClient client;

    /**
     * 创建 MQTT 通道。
     *
     * @param profile 设备配置
     * @param password 本次启动临时使用的密码，不会持久化
     * @param logger 运行日志接收器
     */
    public MqttTransport(SimulatorProfile profile, char[] password, Consumer<String> logger) {
        this.profile = profile;
        this.password = password == null ? new char[0] : password.clone();
        this.logger = logger;
    }

    @Override
    public synchronized void connect() throws IOException {
        disconnectClient();
        state = TransportState.CONNECTING;
        String scheme = profile.tlsEnabled() ? "ssl" : "tcp";
        try {
            client = new MqttClient(scheme + "://" + profile.host() + ":" + profile.port(),
                    profile.clientId(), new MemoryPersistence());
            client.setCallback(new MqttCallbackExtended() {
                @Override
                public void connectComplete(boolean reconnect, String serverUri) {
                    state = TransportState.CONNECTED;
                    logger.accept("MQTT 已连接 " + serverUri);
                }

                @Override
                public void connectionLost(Throwable cause) {
                    state = TransportState.FAILED;
                    logger.accept("MQTT 连接断开：" + (cause == null ? "未知原因" : cause.getMessage()));
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    logger.accept("收到 MQTT 消息 " + topic + "，" + message.getPayload().length + " 字节");
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                    // 发送计数由运行时统一维护。
                }
            });
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setAutomaticReconnect(false);
            options.setConnectionTimeout(10);
            options.setKeepAliveInterval(30);
            if (!profile.username().isBlank()) {
                options.setUserName(profile.username());
            }
            if (password.length > 0) {
                options.setPassword(password);
            }
            client.connect(options);
            state = TransportState.CONNECTED;
            logger.accept("MQTT 已连接 " + scheme + "://" + profile.host() + ":" + profile.port());
        } catch (MqttException exception) {
            state = TransportState.FAILED;
            disconnectClient();
            throw new IOException("MQTT 连接失败：" + exception.getMessage(), exception);
        }
    }

    @Override
    public synchronized void send(byte[] payload) throws IOException {
        if (state != TransportState.CONNECTED || client == null || !client.isConnected()) {
            throw new IOException("MQTT 客户端尚未连接");
        }
        try {
            client.publish(profile.publishTopic(), payload, profile.qos(), false);
        } catch (MqttException exception) {
            state = TransportState.FAILED;
            throw new IOException("MQTT 发布失败：" + exception.getMessage(), exception);
        }
    }

    @Override
    public synchronized void disconnect() {
        disconnectClient();
        state = TransportState.DISCONNECTED;
    }

    @Override
    public TransportState state() {
        return state;
    }

    @Override
    public void close() {
        disconnect();
        Arrays.fill(password, '\0');
    }

    private void disconnectClient() {
        if (client == null) {
            return;
        }
        try {
            if (client.isConnected()) {
                client.disconnect();
            }
            client.close();
        } catch (MqttException ignored) {
            // 断连错误只记录在此前状态中，不阻塞后续重连。
        } finally {
            client = null;
        }
    }
}
