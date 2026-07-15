package com.bosco.mine.examples.simulator;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 基于 Eclipse Paho 的 MQTT 传输实现，仅保存模拟器运行期所需的连接状态。
 */
public final class PahoMqttTransport implements SimulatorRuntime.Transport {

    private final MqttClient client;
    private final MqttConnectOptions options;
    private final char[] password;

    /**
     * 创建 MQTT 传输。
     *
     * @param brokerUri Broker 地址
     * @param clientId 设备 clientId
     * @param username 设备用户名
     * @param password 设备密码，构造器会复制该数组
     */
    public PahoMqttTransport(String brokerUri, String clientId, String username, char[] password) {
        try {
            this.client = new MqttClient(
                    Objects.requireNonNull(brokerUri, "brokerUri 不能为空"),
                    Objects.requireNonNull(clientId, "clientId 不能为空"),
                    new MemoryPersistence()
            );
        } catch (MqttException exception) {
            throw new IllegalStateException("无法创建 MQTT 客户端", exception);
        }
        this.password = Objects.requireNonNull(password, "password 不能为空").clone();
        this.options = new MqttConnectOptions();
        this.options.setUserName(Objects.requireNonNull(username, "username 不能为空"));
        this.options.setPassword(this.password);
        this.options.setCleanSession(true);
        this.options.setAutomaticReconnect(false);
        this.options.setConnectionTimeout(10);
        this.options.setKeepAliveInterval(30);
    }

    /** {@inheritDoc} */
    @Override
    public void connect() {
        if (client.isConnected()) return;
        try {
            client.connect(options);
        } catch (MqttException exception) {
            throw new IllegalStateException("MQTT 连接失败", exception);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void disconnect() {
        if (!client.isConnected()) return;
        try {
            client.disconnect();
        } catch (MqttException exception) {
            throw new IllegalStateException("MQTT 断开失败", exception);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void subscribe(String topic, int qos, Consumer<byte[]> handler) {
        try {
            client.subscribe(topic, qos, (ignored, message) -> handler.accept(message.getPayload().clone()));
        } catch (MqttException exception) {
            throw new IllegalStateException("MQTT 订阅失败: " + topic, exception);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void publish(String topic, byte[] payload, int qos) {
        try {
            client.publish(topic, payload, qos, false);
        } catch (MqttException exception) {
            throw new IllegalStateException("MQTT 发布失败: " + topic, exception);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void close() {
        try {
            if (client.isConnected()) client.disconnect();
            client.close();
        } catch (MqttException exception) {
            throw new IllegalStateException("MQTT 客户端关闭失败", exception);
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
