package com.bosco.mine.simulator.runtime;

import com.bosco.mine.simulator.payload.PayloadRenderer;
import com.bosco.mine.simulator.profile.PayloadFormat;
import com.bosco.mine.simulator.profile.ProfileStore;
import com.bosco.mine.simulator.profile.SimulatorProfile;
import com.bosco.mine.simulator.profile.TransportMode;
import com.bosco.mine.simulator.transport.SimulatorTransport;
import com.bosco.mine.simulator.transport.TransportFactory;
import com.bosco.mine.simulator.transport.TransportState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 验证模拟任务的生命周期、周期发送和运行日志。
 */
class SimulatorRuntimeServiceTest {

    private final FakeTransport transport = new FakeTransport();
    private final SimulatorRuntimeService service = new SimulatorRuntimeService(
            new SingleProfileStore(profile()), new PayloadRenderer(),
            new TransportFactory() {
                @Override
                public SimulatorTransport create(SimulatorProfile profile, char[] password,
                        java.util.function.Consumer<String> logger) {
                    return transport;
                }
            });

    @AfterEach
    void close() {
        service.close();
    }

    @Test
    void 应启动周期发送并支持断连重连和停止() {
        RuntimeSnapshot started = service.start("device-1", new char[0]);

        assertThat(started.running()).isTrue();
        await().atMost(Duration.ofSeconds(2)).until(() -> transport.payloads.size() >= 2);
        service.disconnect("device-1");
        assertThat(service.find("device-1").state()).isEqualTo(TransportState.DISCONNECTED);
        service.reconnect("device-1");
        service.sendOnce("device-1");
        RuntimeSnapshot stopped = service.stop("device-1");

        assertThat(stopped.running()).isFalse();
        assertThat(stopped.sentCount()).isGreaterThanOrEqualTo(3);
        assertThat(stopped.logs()).isNotEmpty();
    }

    private static SimulatorProfile profile() {
        return new SimulatorProfile(
                "device-1", "测试矿卡", TransportMode.TCP_CLIENT, "127.0.0.1", 9000, false,
                "truck", "truck-001", "", "", "", PayloadFormat.JSON,
                "{\"sequence\":${sequence}}", 100, 0);
    }

    private static final class FakeTransport implements SimulatorTransport {
        private final List<byte[]> payloads = new CopyOnWriteArrayList<>();
        private volatile TransportState state = TransportState.DISCONNECTED;

        @Override
        public void connect() {
            state = TransportState.CONNECTED;
        }

        @Override
        public void send(byte[] payload) throws IOException {
            if (state != TransportState.CONNECTED) {
                throw new IOException("未连接");
            }
            payloads.add(payload);
        }

        @Override
        public void disconnect() {
            state = TransportState.DISCONNECTED;
        }

        @Override
        public TransportState state() {
            return state;
        }
    }

    private record SingleProfileStore(SimulatorProfile profile) implements ProfileStore {
        @Override
        public List<SimulatorProfile> findAll() {
            return List.of(profile);
        }

        @Override
        public Optional<SimulatorProfile> findById(String id) {
            return profile.id().equals(id) ? Optional.of(profile) : Optional.empty();
        }

        @Override
        public SimulatorProfile save(SimulatorProfile value) {
            return value;
        }

        @Override
        public boolean delete(String id) {
            return false;
        }
    }
}
