package com.bosco.mine.protocol.engine.tcp;

import com.bosco.mine.protocol.engine.ProtocolValidationException;
import com.bosco.mine.protocol.sdk.tcp.TcpFrameDefinition;
import com.bosco.mine.protocol.sdk.tcp.TcpProtocolPlugin;
import com.bosco.mine.protocol.sdk.tcp.TcpProtocolSession;
import org.junit.jupiter.api.Test;

import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验 TCP 协议插件发现、兼容性和原子注册门禁。
 */
class TcpProtocolPluginRegistryTest {

    @Test
    void 应通过标准ServiceLoader发现插件() {
        TcpProtocolPluginRegistry registry = TcpProtocolPluginRegistry.load(
                Thread.currentThread().getContextClassLoader()
        );

        assertThat(registry.require("test-tcp-binary").protocolVersion())
                .isEqualTo("test-tcp-binary:v1");
        assertThat(registry.protocolKeys()).containsExactly("test-tcp-binary");
    }

    @Test
    void 应支持显式插件集合并提供只读查询() {
        TcpProtocolPlugin plugin = plugin("haul-truck-binary", 1);
        TcpProtocolPluginRegistry registry = new TcpProtocolPluginRegistry(List.of(plugin));

        assertThat(registry.find("haul-truck-binary")).containsSame(plugin);
        assertThat(registry.find("missing")).isEmpty();
        assertThatThrownBy(() -> registry.require("missing"))
                .isInstanceOf(ProtocolValidationException.class)
                .hasMessageContaining("未安装");
    }

    @Test
    void 应拒绝重复协议键且不选择任意一个实现() {
        assertThatThrownBy(() -> new TcpProtocolPluginRegistry(List.of(
                plugin("haul-truck-binary", 1), plugin("haul-truck-binary", 1))))
                .isInstanceOf(ProtocolValidationException.class)
                .extracting("code")
                .isEqualTo("TCP_PLUGIN_DUPLICATE_KEY");
    }

    @Test
    void 应拒绝不兼容API主版本和非法协议键() {
        assertThatThrownBy(() -> new TcpProtocolPluginRegistry(List.of(
                plugin("haul-truck-binary", 2))))
                .isInstanceOf(ProtocolValidationException.class)
                .extracting("code")
                .isEqualTo("TCP_PLUGIN_API_INCOMPATIBLE");
        assertThatThrownBy(() -> new TcpProtocolPluginRegistry(List.of(
                plugin("Bad Key", 1))))
                .isInstanceOf(ProtocolValidationException.class)
                .extracting("code")
                .isEqualTo("TCP_PLUGIN_KEY_INVALID");
    }

    @Test
    void 应拒绝空插件和不完整元数据() {
        assertThatThrownBy(() -> new TcpProtocolPluginRegistry(Arrays.asList(
                plugin("haul-truck-binary", 1), null)))
                .isInstanceOf(ProtocolValidationException.class)
                .extracting("code")
                .isEqualTo("TCP_PLUGIN_INVALID");
        assertThatThrownBy(() -> new TcpProtocolPluginRegistry(List.of(
                new StubPlugin("haul-truck-binary", " ", 1))))
                .isInstanceOf(ProtocolValidationException.class)
                .extracting("code")
                .isEqualTo("TCP_PLUGIN_VERSION_INVALID");
    }

    private TcpProtocolPlugin plugin(String key, int apiVersion) {
        return new StubPlugin(key, key + ":v1", apiVersion);
    }

    private record StubPlugin(String protocolKey, String protocolVersion, int apiMajorVersion)
            implements TcpProtocolPlugin {

        @Override
        public TcpFrameDefinition frameDefinition() {
            return new TcpFrameDefinition(ByteOrder.BIG_ENDIAN, 256, 4, 2, -6, 0);
        }

        @Override
        public TcpProtocolSession openSession() {
            throw new UnsupportedOperationException("本测试不创建协议会话");
        }
    }
}
