package com.bosco.mine.messaging;

import com.bosco.mine.messaging.topology.MqttDevicePermissions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * 校验每设备 MQTT 资源权限和主题权限契约。
 */
class MqttDevicePermissionsTest {

    @Test
    void 应为设备生成最小资源权限和主题权限() {
        MqttDevicePermissions permissions = MqttDevicePermissions.forDevice("truck-001");

        assertThat(permissions.configurePattern())
                .isEqualTo("^mqtt-subscription-truck-001qos[01]$");
        assertThat(permissions.writePattern())
                .isEqualTo("^(amq\\.topic|mqtt-subscription-truck-001qos[01])$");
        assertThat(permissions.readPattern()).isEqualTo(permissions.writePattern());
        assertThat(permissions.topicWritePattern())
                .isEqualTo("^mine\\.v1\\.[^.]+\\.truck-001\\.up\\.(telemetry|location|command-ack)$");
        assertThat(permissions.topicReadPattern())
                .isEqualTo("^mine\\.v1\\.[^.]+\\.truck-001\\.down\\.[^.]+$");
    }

    @Test
    void 应拒绝可能改变主题层级或正则含义的设备标识() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MqttDevicePermissions.forDevice("truck.001"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MqttDevicePermissions.forDevice("truck/001"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MqttDevicePermissions.forDevice("truck+"));
    }

    @Test
    void 指定产品时应锁定完整产品设备主题根() {
        MqttDevicePermissions permissions = MqttDevicePermissions.forDevice(
                "haul-truck", "device-001"
        );

        assertThat(permissions.topicWritePattern())
                .isEqualTo("^mine\\.v1\\.haul-truck\\.device-001\\.up\\.(telemetry|location|command-ack)$");
        assertThat(permissions.topicReadPattern())
                .isEqualTo("^mine\\.v1\\.haul-truck\\.device-001\\.down\\.[^.]+$");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MqttDevicePermissions.forDevice("haul.truck", "device-001"));
    }
}
