package com.bosco.mine.server.modules.mine.scene;

import com.bosco.mine.server.modules.mine.scene.domain.SceneAssetPolicy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleSupplier;

/**
 * 汇总三维场景资源、发布、读取和运行快照的低基数运行指标。
 *
 * <p>计数器标签只能来自本类有限枚举。组织、矿区、场景、资源、文件路径、
 * 用户身份和异常类型或文本均不得进入指标。</p>
 */
@Component
public final class SceneMetrics {

    private static final Logger LOGGER = LoggerFactory.getLogger(SceneMetrics.class);
    private static final Set<String> WARNED_METRICS = ConcurrentHashMap.newKeySet();
    private static final String OPERATIONS_METRIC = "mine.server.scene.operations";
    private static final String QUARANTINED_ASSETS_METRIC =
            "mine.server.scene.assets.quarantined";
    private static final String STORAGE_AVAILABLE_METRIC =
            "mine.server.scene.storage.available";

    private final MeterRegistry registry;
    private final AtomicReference<Double> storageAvailability =
            new AtomicReference<>(Double.NaN);

    /**
     * 创建场景指标，并从管理数据库读取当前隔离资源数量。
     *
     * @param registry Micrometer 指标注册表
     * @param dataSource 管理数据库数据源
     */
    @Autowired
    public SceneMetrics(MeterRegistry registry, DataSource dataSource) {
        this(registry, quarantinedAssetSupplier(dataSource));
    }

    /**
     * 创建可使用固定读取器验证的场景指标。
     *
     * @param registry Micrometer 指标注册表
     * @param quarantinedAssets 当前隔离资源数量读取器
     */
    public SceneMetrics(MeterRegistry registry, DoubleSupplier quarantinedAssets) {
        this.registry = Objects.requireNonNull(registry, "场景指标注册表不能为空");
        registerGauge(
                QUARANTINED_ASSETS_METRIC,
                "当前处于隔离状态且未进入已发布场景的三维资源数量",
                safeGauge(Objects.requireNonNull(
                        quarantinedAssets, "隔离场景资源读取器不能为空")));
        registerGauge(
                STORAGE_AVAILABLE_METRIC,
                "三维场景资源专用读写删除探针可用状态，未知为 NaN",
                storageAvailability::get);
    }

    /**
     * 记录固定场景阶段的处理结果；存在事务时只在事务成功提交后增加。
     *
     * @param stage 固定场景阶段
     * @param result 固定处理结果
     * @param reason 固定处理原因
     * @param assetType 固定资源类型；非资源阶段使用 {@link AssetType#NONE}
     */
    public void operation(Stage stage, Result result, Reason reason, AssetType assetType) {
        incrementAfterCommit(counter(stage, result, reason, assetType));
    }

    /**
     * 立即记录已经确定且不依赖业务事务提交的失败结果。
     *
     * <p>该入口只允许拒绝或失败结果，用于上传流校验、存储访问和发布冲突；
     * 成功或降级快照仍必须通过 {@link #operation(Stage, Result, Reason, AssetType)}
     * 在事务提交后形成指标事实。</p>
     *
     * @param stage 固定场景阶段
     * @param result 只能为拒绝或失败
     * @param reason 固定失败原因
     * @param assetType 固定资源类型
     */
    public void failure(Stage stage, Result result, Reason reason, AssetType assetType) {
        if (result != Result.REJECTED && result != Result.FAILURE) {
            throw new IllegalArgumentException("即时场景指标只允许拒绝或失败结果");
        }
        counter(stage, result, reason, assetType).run();
    }

    private Runnable counter(
            Stage stage,
            Result result,
            Reason reason,
            AssetType assetType
    ) {
        Stage safeStage = Objects.requireNonNull(stage, "场景指标阶段不能为空");
        Result safeResult = Objects.requireNonNull(result, "场景指标结果不能为空");
        Reason safeReason = Objects.requireNonNull(reason, "场景指标原因不能为空");
        AssetType safeAssetType = Objects.requireNonNull(
                assetType, "场景指标资源类型不能为空");
        return () -> {
            try {
                Counter.builder(OPERATIONS_METRIC)
                        .description("三维场景固定阶段处理结果")
                        .tag("stage", safeStage.tag)
                        .tag("result", safeResult.tag)
                        .tag("reason", safeReason.tag)
                        .tag("assetType", safeAssetType.tag)
                        .register(registry)
                        .increment();
            } catch (RuntimeException exception) {
                warnOnce(OPERATIONS_METRIC, exception);
            }
        };
    }

    /**
     * 更新最近一次专用存储探针结果，不抛出指标相关异常。
     *
     * @param available 存储探针是否成功
     */
    public void storageAvailable(boolean available) {
        try {
            storageAvailability.set(available ? 1D : 0D);
        } catch (RuntimeException exception) {
            warnOnce(STORAGE_AVAILABLE_METRIC, exception);
        }
    }

    private void incrementAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        try {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            action.run();
                        }
                    });
        } catch (RuntimeException exception) {
            warnOnce(OPERATIONS_METRIC, exception);
        }
    }

    private void registerGauge(String name, String description, DoubleSupplier supplier) {
        try {
            Gauge.builder(name, supplier, DoubleSupplier::getAsDouble)
                    .description(description)
                    .strongReference(true)
                    .register(registry);
        } catch (RuntimeException exception) {
            warnOnce(name, exception);
        }
    }

    private static DoubleSupplier quarantinedAssetSupplier(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(Objects.requireNonNull(
                dataSource, "场景指标数据源不能为空"));
        return () -> {
            Long value = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM mine_scene_asset
                    WHERE status = 'QUARANTINED'
                    """, Long.class);
            return value == null ? 0D : value.doubleValue();
        };
    }

    private static DoubleSupplier safeGauge(DoubleSupplier supplier) {
        return () -> {
            try {
                return supplier.getAsDouble();
            } catch (RuntimeException exception) {
                return Double.NaN;
            }
        };
    }

    private static void warnOnce(String metric, RuntimeException exception) {
        if (WARNED_METRICS.add(metric)) {
            LOGGER.warn("三维场景指标更新失败，业务处理不受影响: metric={}, errorType={}",
                    metric, exception.getClass().getSimpleName());
        }
    }

    /** 三维场景固定处理阶段。 */
    public enum Stage {
        /** 上传资源并登记清单。 */
        UPLOAD("upload"),
        /** 校验资源安全边界。 */
        VALIDATION("validation"),
        /** 发布不可变场景版本。 */
        PUBLISH("publish"),
        /** 读取已授权资源文件。 */
        READ("read"),
        /** 组装默认场景运行快照。 */
        SNAPSHOT("snapshot");

        private final String tag;

        Stage(String tag) {
            this.tag = tag;
        }
    }

    /** 三维场景固定处理结果。 */
    public enum Result {
        /** 处理成功。 */
        SUCCESS("success"),
        /** 输入被安全边界拒绝。 */
        REJECTED("rejected"),
        /** 处理失败。 */
        FAILURE("failure"),
        /** 三维运行快照已安全降级。 */
        DEGRADED("degraded");

        private final String tag;

        Result(String tag) {
            this.tag = tag;
        }
    }

    /** 三维场景固定处理原因。 */
    public enum Reason {
        /** 正常处理。 */
        NONE("none"),
        /** 上传包不满足安全边界。 */
        INVALID_PACKAGE("invalid_package"),
        /** 三维资源存储不可用。 */
        STORAGE_UNAVAILABLE("storage_unavailable"),
        /** 矿区尚未设置默认场景。 */
        NO_DEFAULT_SCENE("no_default_scene"),
        /** 默认场景尚未发布版本。 */
        DEFAULT_SCENE_UNPUBLISHED("default_scene_unpublished"),
        /** 默认场景结构版本不受支持。 */
        UNSUPPORTED_SCHEMA_VERSION("unsupported_schema_version"),
        /** 已发布场景快照不完整或摘要不一致。 */
        INVALID_PUBLISHED_SNAPSHOT("invalid_published_snapshot"),
        /** 草稿定义未通过发布校验。 */
        INVALID_DEFINITION("invalid_definition"),
        /** 乐观锁或发布状态冲突。 */
        CONFLICT("conflict");

        private final String tag;

        Reason(String tag) {
            this.tag = tag;
        }
    }

    /** 三维场景固定资源类型。 */
    public enum AssetType {
        /** 二进制 glTF 单文件。 */
        GLB("glb"),
        /** 受控压缩包形式的 3D Tiles。 */
        TILES_3D("tiles_3d"),
        /** 当前阶段不对应单一资源类型。 */
        NONE("none");

        private final String tag;

        AssetType(String tag) {
            this.tag = tag;
        }

        /**
         * 将领域资源类型映射为固定指标类型。
         *
         * @param assetType 领域资源类型
         * @return 固定指标资源类型
         */
        public static AssetType from(SceneAssetPolicy.SceneAssetType assetType) {
            return switch (Objects.requireNonNull(assetType, "资源类型不能为空")) {
                case GLB -> GLB;
                case TILES_3D -> TILES_3D;
            };
        }
    }
}
