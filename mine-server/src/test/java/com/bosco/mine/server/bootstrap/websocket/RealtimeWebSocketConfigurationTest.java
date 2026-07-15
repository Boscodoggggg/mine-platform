package com.bosco.mine.server.bootstrap.websocket;

import com.bosco.mine.server.modules.device.service.DeviceTelemetryQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 校验统一实时端点注册和最新值快照委托关系。
 */
class RealtimeWebSocketConfigurationTest {

    @Test
    void 应将处理器注册到配置的统一实时路径() {
        RealtimeWebSocketHandler handler = mock(RealtimeWebSocketHandler.class);
        WebSocketHandlerRegistry registry = mock(WebSocketHandlerRegistry.class);
        RealtimeProperties properties = new RealtimeProperties(
                true, "/ws/realtime", "server-a", null, null, null, null,
                false, null, null, null, null,
                new RealtimeProperties.Account("unused", Path.of("/tmp/unused"))
        );

        WebSocketConfigurer configurer = new RealtimeWebSocketConfiguration()
                .realtimeWebSocketConfigurer(handler, properties);
        configurer.registerWebSocketHandlers(registry);

        verify(registry).addHandler(handler, "/ws/realtime");
    }

    @Test
    void 快照读取器应委托给设备遥测查询服务() {
        DeviceTelemetryQueryService service = mock(DeviceTelemetryQueryService.class);
        UUID deviceId = UUID.randomUUID();

        RealtimeSnapshotProvider provider = new RealtimeWebSocketConfiguration()
                .realtimeSnapshotProvider(service);
        provider.load(deviceId, List.of("speed"));

        verify(service).findLatest(deviceId, List.of("speed"));
    }
}
