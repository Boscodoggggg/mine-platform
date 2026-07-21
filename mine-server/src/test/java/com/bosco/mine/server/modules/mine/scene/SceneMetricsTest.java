package com.bosco.mine.server.modules.mine.scene;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证三维场景指标只记录已提交低基数事实，并在依赖不可用时安全降级。
 */
class SceneMetricsTest {

    @Test
    void 场景操作计数应只在事务提交后增加且仅包含固定标签() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SceneMetrics metrics = new SceneMetrics(registry, () -> 2D);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            metrics.operation(
                    SceneMetrics.Stage.UPLOAD,
                    SceneMetrics.Result.SUCCESS,
                    SceneMetrics.Reason.NONE,
                    SceneMetrics.AssetType.GLB);

            assertThat(registry.find("mine.server.scene.operations").counter()).isNull();
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            assertThat(registry.get("mine.server.scene.operations")
                    .tags("stage", "upload", "result", "success",
                            "reason", "none", "assetType", "glb")
                    .counter().count()).isOne();
            assertThat(registry.getMeters()).allSatisfy(meter ->
                    assertThat(meter.getId().getTags()).allSatisfy(tag ->
                            assertThat(tag.getKey()).isIn(Set.of(
                                    "stage", "result", "reason", "assetType"))));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void 未提交事务不得形成场景操作计数() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SceneMetrics metrics = new SceneMetrics(registry, () -> 0D);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            metrics.operation(
                    SceneMetrics.Stage.PUBLISH,
                    SceneMetrics.Result.SUCCESS,
                    SceneMetrics.Reason.NONE,
                    SceneMetrics.AssetType.NONE);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        assertThat(registry.find("mine.server.scene.operations").counter()).isNull();
    }

    @Test
    void 已确定校验拒绝应立即计数且不等待回滚事务() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SceneMetrics metrics = new SceneMetrics(registry, () -> 0D);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            metrics.failure(
                    SceneMetrics.Stage.VALIDATION,
                    SceneMetrics.Result.REJECTED,
                    SceneMetrics.Reason.INVALID_PACKAGE,
                    SceneMetrics.AssetType.TILES_3D);

            assertThat(registry.get("mine.server.scene.operations")
                    .tags("stage", "validation", "result", "rejected",
                            "reason", "invalid_package", "assetType", "tiles_3d")
                    .counter().count()).isOne();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void 隔离资源查询失败返回NaN且存储健康状态显式变化() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicInteger reads = new AtomicInteger();
        SceneMetrics metrics = new SceneMetrics(registry, () -> {
            reads.incrementAndGet();
            throw new IllegalStateException("database /secret/path unavailable");
        });

        assertThat(registry.get("mine.server.scene.assets.quarantined").gauge().value())
                .isNaN();
        assertThat(reads).hasValue(1);
        assertThat(registry.get("mine.server.scene.storage.available").gauge().value())
                .isNaN();

        metrics.storageAvailable(true);
        assertThat(registry.get("mine.server.scene.storage.available").gauge().value())
                .isOne();
        metrics.storageAvailable(false);
        assertThat(registry.get("mine.server.scene.storage.available").gauge().value())
                .isZero();
    }

    @Test
    void 指标注册或更新失败不得改变场景业务处理() {
        SimpleMeterRegistry closedRegistry = new SimpleMeterRegistry();
        closedRegistry.close();

        SceneMetrics[] metrics = new SceneMetrics[1];
        assertThatCode(() -> metrics[0] = new SceneMetrics(closedRegistry, () -> 0D))
                .doesNotThrowAnyException();
        assertThatCode(() -> metrics[0].operation(
                SceneMetrics.Stage.READ,
                SceneMetrics.Result.FAILURE,
                SceneMetrics.Reason.STORAGE_UNAVAILABLE,
                SceneMetrics.AssetType.TILES_3D)).doesNotThrowAnyException();
        assertThatCode(() -> metrics[0].storageAvailable(false))
                .doesNotThrowAnyException();
    }

    @Test
    void 场景指标不得接受路径异常或业务身份作为标签() {
        assertThat(SceneMetrics.Stage.values()).extracting(Enum::name)
                .containsExactly("UPLOAD", "VALIDATION", "PUBLISH", "READ", "SNAPSHOT");
        assertThat(SceneMetrics.AssetType.values()).extracting(Enum::name)
                .containsExactly("GLB", "TILES_3D", "NONE");
        assertThat(SceneMetrics.class.getDeclaredMethods())
                .filteredOn(method -> method.getName().equals("operation"))
                .singleElement()
                .satisfies(method -> assertThat(method.getParameterTypes())
                        .containsExactly(
                                SceneMetrics.Stage.class,
                                SceneMetrics.Result.class,
                                SceneMetrics.Reason.class,
                                SceneMetrics.AssetType.class));
    }

    @Test
    void 场景读取应在完整流结束后记录成功且中途断流记录失败() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SceneMetrics metrics = new SceneMetrics(registry, () -> 0D);
        try (InputStream input = new SceneAssetService.SceneReadInputStream(
                new ByteArrayInputStream(new byte[]{1, 2, 3}),
                3,
                metrics,
                SceneMetrics.AssetType.GLB)) {
            assertThat(input.readAllBytes()).containsExactly(1, 2, 3);
        }
        assertThat(registry.get("mine.server.scene.operations")
                .tags("stage", "read", "result", "success",
                        "reason", "none", "assetType", "glb")
                .counter().count()).isOne();

        InputStream broken = new InputStream() {
            private int reads;

            @Override
            public int read() throws IOException {
                if (reads++ == 0) {
                    return 1;
                }
                throw new IOException("s3://secret/path");
            }
        };
        try (InputStream input = new SceneAssetService.SceneReadInputStream(
                broken,
                3,
                metrics,
                SceneMetrics.AssetType.TILES_3D)) {
            assertThatThrownBy(input::readAllBytes).isInstanceOf(IOException.class);
        }
        assertThat(registry.get("mine.server.scene.operations")
                .tags("stage", "read", "result", "failure",
                        "reason", "storage_unavailable", "assetType", "tiles_3d")
                .counter().count()).isOne();
    }
}
