package com.bosco.mine.gateway.session;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * 校验设备身份注册表只允许单调版本激活，并始终保留上一份有效快照。
 */
class DeviceSessionRegistryVersionTest {

    private static final String DIGEST_1 = "1111111111111111111111111111111111111111111111111111111111111111";
    private static final String DIGEST_2 = "2222222222222222222222222222222222222222222222222222222222222222";
    private static final String DIGEST_3 = "3333333333333333333333333333333333333333333333333333333333333333";

    @Test
    void 首次快照和更高版本应原子生效() {
        DeviceSessionRegistry registry = new DeviceSessionRegistry();

        assertThat(registry.activate(1, DIGEST_1, List.of(identity("client-001", "019f63e2-071f-706a-bd12-4dad7150e75d"))))
                .isEqualTo(DeviceSessionRegistry.ActivationResult.ACTIVATED);
        assertThat(registry.activate(2, DIGEST_2, List.of(identity("client-002", "019f63e2-071f-706a-bd12-4dad7150e75e"))))
                .isEqualTo(DeviceSessionRegistry.ActivationResult.ACTIVATED);

        assertThat(registry.find("haul-truck", "client-001")).isEmpty();
        assertThat(registry.find("haul-truck", "client-002")).isPresent();
        assertThat(registry.status()).isEqualTo(new DeviceSessionRegistry.SnapshotStatus(2, DIGEST_2, 1));
    }

    @Test
    void 同版本同摘要应视为幂等且不替换当前身份() {
        DeviceSessionRegistry registry = activatedRegistry();

        assertThat(registry.activate(2, DIGEST_2, List.of(identity("client-002", "019f63e2-071f-706a-bd12-4dad7150e75e"))))
                .isEqualTo(DeviceSessionRegistry.ActivationResult.UNCHANGED);

        assertThat(registry.find("haul-truck", "client-002")).isPresent();
        assertThat(registry.status()).isEqualTo(new DeviceSessionRegistry.SnapshotStatus(2, DIGEST_2, 1));
    }

    @Test
    void 同版本异摘要应拒绝并保留当前快照() {
        DeviceSessionRegistry registry = activatedRegistry();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> registry.activate(2, DIGEST_3, List.of(identity("client-003", "019f63e2-071f-706a-bd12-4dad7150e75f"))))
                .withMessageContaining("相同版本");

        assertLastKnownGood(registry);
    }

    @Test
    void 低版本应拒绝并保留当前快照() {
        DeviceSessionRegistry registry = activatedRegistry();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> registry.activate(1, DIGEST_1, List.of(identity("client-001", "019f63e2-071f-706a-bd12-4dad7150e75d"))))
                .withMessageContaining("低于当前版本");

        assertLastKnownGood(registry);
    }

    @Test
    void 非法版本和摘要不得进入注册表() {
        DeviceSessionRegistry registry = new DeviceSessionRegistry();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> registry.activate(0, DIGEST_1, List.of()))
                .withMessageContaining("版本");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> registry.activate(1, "not-sha256", List.of()))
                .withMessageContaining("SHA-256");
    }

    @Test
    void 应按产品和设备编码反查TCP可信身份() {
        DeviceSessionRegistry registry = new DeviceSessionRegistry();
        DeviceSessionRegistry.DeviceIdentity truck = identity(
                "haul-truck", "client-001", "019f63e2-071f-706a-bd12-4dad7150e75d",
                "truck-001", "haul-truck-binary:v1");
        DeviceSessionRegistry.DeviceIdentity loader = identity(
                "loader", "client-002", "019f63e2-071f-706a-bd12-4dad7150e75e",
                "truck-001", "loader-binary:v1");
        registry.activate(1, DIGEST_1, List.of(truck, loader));

        assertThat(registry.findByDeviceCode("haul-truck", "truck-001")).containsSame(truck);
        assertThat(registry.findByDeviceCode("loader", "truck-001")).containsSame(loader);
        assertThat(registry.findByDeviceCode("haul-truck", "missing")).isEmpty();
    }

    @Test
    void 同一产品重复设备编码应拒绝且不得替换最后正确快照() {
        DeviceSessionRegistry registry = activatedRegistry();

        assertThatIllegalArgumentException().isThrownBy(() -> registry.activate(
                3, DIGEST_3, List.of(
                        identity("haul-truck", "client-003",
                                "019f63e2-071f-706a-bd12-4dad7150e75f", "truck-003", "binary:v1"),
                        identity("haul-truck", "client-004",
                                "019f63e2-071f-706a-bd12-4dad7150e760", "truck-003", "binary:v1")
                )))
                .withMessageContaining("TCP");

        assertLastKnownGood(registry);
    }

    @Test
    void 并发激活不同版本时最终必须保留最高版本() throws Exception {
        DeviceSessionRegistry registry = new DeviceSessionRegistry();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ActivationOutcome> version1 = submit(
                    executor, ready, start,
                    () -> registry.activate(1, DIGEST_1, List.of(
                            identity("client-001", "019f63e2-071f-706a-bd12-4dad7150e75d")
                    ))
            );
            Future<ActivationOutcome> version2 = submit(
                    executor, ready, start,
                    () -> registry.activate(2, DIGEST_2, List.of(
                            identity("client-002", "019f63e2-071f-706a-bd12-4dad7150e75e")
                    ))
            );

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            ActivationOutcome version1Outcome = version1.get(5, TimeUnit.SECONDS);
            ActivationOutcome version2Outcome = version2.get(5, TimeUnit.SECONDS);

            assertThat(version2Outcome.result()).isEqualTo(DeviceSessionRegistry.ActivationResult.ACTIVATED);
            assertThat(version2Outcome.failure()).isNull();
            assertThat(version1Outcome).satisfies(outcome -> {
                if (outcome.failure() == null) {
                    assertThat(outcome.result()).isEqualTo(DeviceSessionRegistry.ActivationResult.ACTIVATED);
                } else {
                    assertThat(outcome.result()).isNull();
                    assertThat(outcome.failure()).isInstanceOf(IllegalArgumentException.class);
                }
            });
            assertThat(registry.status()).isEqualTo(new DeviceSessionRegistry.SnapshotStatus(2, DIGEST_2, 1));
            assertThat(registry.find("haul-truck", "client-001")).isEmpty();
            assertThat(registry.find("haul-truck", "client-002")).isPresent();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 并发激活同版本异摘要时只能一个生效() throws Exception {
        DeviceSessionRegistry registry = new DeviceSessionRegistry();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ActivationOutcome> first = submit(
                    executor, ready, start,
                    () -> registry.activate(1, DIGEST_1, List.of(
                            identity("client-001", "019f63e2-071f-706a-bd12-4dad7150e75d")
                    ))
            );
            Future<ActivationOutcome> second = submit(
                    executor, ready, start,
                    () -> registry.activate(1, DIGEST_2, List.of(
                            identity("client-002", "019f63e2-071f-706a-bd12-4dad7150e75e")
                    ))
            );

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<ActivationOutcome> outcomes = List.of(
                    first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)
            );

            assertThat(outcomes).filteredOn(outcome -> outcome.result() == DeviceSessionRegistry.ActivationResult.ACTIVATED)
                    .hasSize(1);
            assertThat(outcomes).filteredOn(outcome -> outcome.failure() instanceof IllegalArgumentException)
                    .hasSize(1);
            DeviceSessionRegistry.SnapshotStatus status = registry.status();
            assertThat(status.version()).isEqualTo(1);
            if (status.sha256().equals(DIGEST_1)) {
                assertThat(registry.find("haul-truck", "client-001")).isPresent();
                assertThat(registry.find("haul-truck", "client-002")).isEmpty();
            } else {
                assertThat(status.sha256()).isEqualTo(DIGEST_2);
                assertThat(registry.find("haul-truck", "client-001")).isEmpty();
                assertThat(registry.find("haul-truck", "client-002")).isPresent();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private DeviceSessionRegistry activatedRegistry() {
        DeviceSessionRegistry registry = new DeviceSessionRegistry();
        registry.activate(2, DIGEST_2, List.of(identity("client-002", "019f63e2-071f-706a-bd12-4dad7150e75e")));
        return registry;
    }

    private void assertLastKnownGood(DeviceSessionRegistry registry) {
        assertThat(registry.find("haul-truck", "client-002")).isPresent();
        assertThat(registry.status()).isEqualTo(new DeviceSessionRegistry.SnapshotStatus(2, DIGEST_2, 1));
    }

    private DeviceSessionRegistry.DeviceIdentity identity(String clientId, String deviceId) {
        return identity("haul-truck", clientId, deviceId, "truck-001", "mqtt-json-v1");
    }

    private DeviceSessionRegistry.DeviceIdentity identity(
            String productKey,
            String clientId,
            String deviceId,
            String deviceCode,
            String protocolVersion
    ) {
        return new DeviceSessionRegistry.DeviceIdentity(
                productKey, clientId, deviceId, deviceCode, "mine-site-001", protocolVersion
        );
    }

    private Future<ActivationOutcome> submit(
            ExecutorService executor,
            CountDownLatch ready,
            CountDownLatch start,
            Activation activation
    ) {
        return executor.submit(() -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                return new ActivationOutcome(null, new IllegalStateException("并发测试启动超时"));
            }
            try {
                return new ActivationOutcome(activation.activate(), null);
            } catch (IllegalArgumentException exception) {
                return new ActivationOutcome(null, exception);
            }
        });
    }

    /**
     * 表示并发激活任务的成功结果或预期拒绝。
     *
     * @param result 激活结果
     * @param failure 激活拒绝异常
     */
    private record ActivationOutcome(DeviceSessionRegistry.ActivationResult result, Throwable failure) {
    }

    /**
     * 延迟执行一次候选快照激活。
     */
    @FunctionalInterface
    private interface Activation {
        /**
         * 执行激活。
         *
         * @return 激活结果
         */
        DeviceSessionRegistry.ActivationResult activate();
    }
}
