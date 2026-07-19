package com.bosco.mine.protocol.engine.binding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验产品协议绑定只接受平台能够安全执行的组合。
 */
class ProductProtocolBindingTest {

    @Test
    void 应接受MQTT配置解析和MQTT二进制插件() {
        ProductProtocolBinding json = new ProductProtocolBinding(
                ProductProtocolBinding.Transport.MQTT,
                ProductProtocolBinding.PayloadFormat.JSON,
                ProductProtocolBinding.RoutingMode.TRUSTED_IDENTITY,
                ProductProtocolBinding.ParserType.CONFIGURATION,
                null,
                null
        );
        ProductProtocolBinding binary = new ProductProtocolBinding(
                ProductProtocolBinding.Transport.MQTT,
                ProductProtocolBinding.PayloadFormat.BINARY,
                ProductProtocolBinding.RoutingMode.TRUSTED_IDENTITY,
                ProductProtocolBinding.ParserType.JAVA_PLUGIN,
                "haul-truck-mqtt-binary",
                "haul-truck-mqtt-binary:v1"
        );

        assertThat(json.usesConfiguration()).isTrue();
        assertThat(binary.usesJavaPlugin()).isTrue();
    }

    @Test
    void 应接受TCP统一和兼容监听插件() {
        assertThat(tcp(ProductProtocolBinding.RoutingMode.TRUSTED_IDENTITY).usesJavaPlugin())
                .isTrue();
        assertThat(tcp(ProductProtocolBinding.RoutingMode.LEGACY_LISTENER).usesJavaPlugin())
                .isTrue();
    }

    @Test
    void 应拒绝无法安全执行的组合和缺失插件元数据() {
        assertThatThrownBy(() -> new ProductProtocolBinding(
                ProductProtocolBinding.Transport.TCP,
                ProductProtocolBinding.PayloadFormat.JSON,
                ProductProtocolBinding.RoutingMode.TRUSTED_IDENTITY,
                ProductProtocolBinding.ParserType.CONFIGURATION,
                null,
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("组合");
        assertThatThrownBy(() -> new ProductProtocolBinding(
                ProductProtocolBinding.Transport.MQTT,
                ProductProtocolBinding.PayloadFormat.BINARY,
                ProductProtocolBinding.RoutingMode.TRUSTED_IDENTITY,
                ProductProtocolBinding.ParserType.JAVA_PLUGIN,
                null,
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pluginKey");
    }

    private ProductProtocolBinding tcp(ProductProtocolBinding.RoutingMode routingMode) {
        return new ProductProtocolBinding(
                ProductProtocolBinding.Transport.TCP,
                ProductProtocolBinding.PayloadFormat.BINARY,
                routingMode,
                ProductProtocolBinding.ParserType.JAVA_PLUGIN,
                "haul-truck-binary",
                "haul-truck-binary:v1"
        );
    }
}
