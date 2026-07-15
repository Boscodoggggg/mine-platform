package com.bosco.mine.gateway.bootstrap;

import com.bosco.mine.gateway.mqtt.DeviceMessageConsumer;
import com.bosco.mine.gateway.session.DeviceIdentitySnapshotLoader;
import com.bosco.mine.gateway.session.DeviceSessionRegistry;
import com.bosco.mine.messaging.topology.MineTopology;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验 gateway 使用两套最小权限 RabbitMQ 连接，而不是共享默认 vhost 连接。
 */
class GatewayRabbitConfigurationTest {

    @TempDir
    private Path tempDirectory;

    @Test
    void 启用后应分别装配设备消费连接和平台发布连接() throws Exception {
        Path devicePassword = Files.writeString(tempDirectory.resolve("device-password"), "device-secret\n");
        Path platformPassword = Files.writeString(tempDirectory.resolve("platform-password"), "platform-secret\n");
        Path identities = Files.writeString(
                tempDirectory.resolve("device-identities.json"),
                "{\"version\":1,\"devices\":[]}"
        );

        new ApplicationContextRunner()
                .withUserConfiguration(GatewayRabbitConfiguration.class)
                .withPropertyValues(
                        "mine.gateway.rabbit.enabled=true",
                        "mine.gateway.rabbit.auto-startup=false",
                        "mine.gateway.rabbit.host=127.0.0.1",
                        "mine.gateway.rabbit.port=5672",
                        "mine.gateway.rabbit.device.username=mine-gateway-device",
                        "mine.gateway.rabbit.device.password-file=" + devicePassword,
                        "mine.gateway.rabbit.platform.username=mine-gateway-platform",
                        "mine.gateway.rabbit.platform.password-file=" + platformPassword,
                        "mine.gateway.rabbit.identity-snapshot-file=" + identities
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(DeviceSessionRegistry.class);
                    assertThat(context).hasSingleBean(DeviceIdentitySnapshotLoader.class);
                    assertThat(context).hasSingleBean(DeviceMessageConsumer.class);
                    CachingConnectionFactory device = context.getBean(
                            "deviceConnectionFactory", CachingConnectionFactory.class
                    );
                    CachingConnectionFactory platform = context.getBean(
                            "platformConnectionFactory", CachingConnectionFactory.class
                    );
                    assertThat(device.getRabbitConnectionFactory().getVirtualHost())
                            .isEqualTo(MineTopology.DEVICE_VHOST);
                    assertThat(platform.getRabbitConnectionFactory().getVirtualHost())
                            .isEqualTo(MineTopology.PLATFORM_VHOST);
                    assertThat(device.getRabbitConnectionFactory().getUsername())
                            .isEqualTo("mine-gateway-device");
                    assertThat(platform.getRabbitConnectionFactory().getUsername())
                            .isEqualTo("mine-gateway-platform");
                    SimpleMessageListenerContainer listener = context.getBean(
                            SimpleMessageListenerContainer.class
                    );
                    assertThat(listener.getQueueNames()).containsExactly(MineTopology.DEVICE_UPLINK_QUEUE);
                    assertThat(listener.isAutoStartup()).isFalse();
                });
    }

    @Test
    void 默认关闭时不应创建RabbitMQ连接() {
        new ApplicationContextRunner()
                .withUserConfiguration(GatewayRabbitConfiguration.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(CachingConnectionFactory.class);
                    assertThat(context).doesNotHaveBean(DeviceMessageConsumer.class);
                });
    }
}
