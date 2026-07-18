package com.bosco.mine.telemetry.bootstrap;

import com.bosco.mine.messaging.topology.MineTopology;
import com.bosco.mine.telemetry.alarm.AlarmPolicyRepository;
import com.bosco.mine.telemetry.alarm.AlarmMetrics;
import com.bosco.mine.telemetry.alarm.outbox.AlarmOutboxEventObserver;
import com.bosco.mine.telemetry.alarm.outbox.RabbitAlarmOutboxPublisher;
import com.bosco.mine.telemetry.alarm.outbox.TelemetryOutboxRecovery;
import com.bosco.mine.telemetry.alarm.outbox.TelemetryOutboxRelay;
import com.bosco.mine.telemetry.alarm.outbox.TelemetryOutboxRepository;
import com.bosco.mine.telemetry.consumer.NormalizedTelemetryConsumer;
import com.bosco.mine.telemetry.consumer.TelemetrySequenceGapMetrics;
import com.bosco.mine.telemetry.consumer.TelemetrySequenceGapObserver;
import com.bosco.mine.telemetry.latest.LatestValueRepository;
import com.bosco.mine.telemetry.location.ConfirmedRealtimeLocationPublisher;
import com.bosco.mine.telemetry.location.LocationFactWriter;
import com.bosco.mine.telemetry.location.LocationLatestCache;
import com.bosco.mine.telemetry.location.LocationObservationConsumer;
import com.bosco.mine.telemetry.location.LocationQualityEvaluator;
import com.bosco.mine.telemetry.location.LocationRealtimeOutboxRelay;
import com.bosco.mine.telemetry.location.LocationRecoveryCoordinator;
import com.bosco.mine.telemetry.location.snapshot.HttpLocationSnapshotSource;
import com.bosco.mine.telemetry.location.snapshot.LocationRuntimeRegistry;
import com.bosco.mine.telemetry.location.snapshot.LocationSnapshotConsumer;
import com.bosco.mine.telemetry.location.snapshot.LocationSnapshotLoader;
import com.bosco.mine.telemetry.location.snapshot.LocationSnapshotMetrics;
import com.bosco.mine.telemetry.location.transform.CoordinateTransformRegistry;
import com.bosco.mine.telemetry.quality.DataQualityEvaluator;
import com.bosco.mine.telemetry.quality.DataQualityPolicyRepository;
import com.bosco.mine.telemetry.quality.QualityEvidenceMetrics;
import com.bosco.mine.telemetry.realtime.ConfirmedRealtimePublisher;
import com.bosco.mine.telemetry.storage.TelemetryBatchWriter;
import com.bosco.mine.telemetry.strategy.HttpStrategySnapshotSource;
import com.bosco.mine.telemetry.strategy.StrategyPolicyRepository;
import com.bosco.mine.telemetry.strategy.StrategyDistributionMetrics;
import com.bosco.mine.telemetry.strategy.StrategyRuntimeConsumer;
import com.bosco.mine.telemetry.strategy.StrategySnapshotLoader;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import io.micrometer.core.instrument.MeterRegistry;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 装配遥测服务的 RabbitMQ 批消费者、Redis 最新值仓储和 TimescaleDB 历史批写器。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        TelemetryRabbitProperties.class,
        StrategyDistributionProperties.class,
        LocationDistributionProperties.class
})
@ConditionalOnProperty(prefix = "mine.telemetry.rabbit", name = "enabled", havingValue = "true")
public class TelemetryStorageConfiguration {

    /**
     * 创建 Redis 实时最新值仓储。
     *
     * @param redisTemplate Redis 字符串操作模板
     * @return 最新值仓储
     */
    @Bean
    public LatestValueRepository latestValueRepository(StringRedisTemplate redisTemplate) {
        return new LatestValueRepository(redisTemplate);
    }

    /**
     * 创建 TimescaleDB 事务批写器。
     *
     * @param dataSource 时序数据库连接池
     * @return 遥测历史批写器
     */
    @Bean
    public TelemetryBatchWriter telemetryBatchWriter(DataSource dataSource) {
        return new TelemetryBatchWriter(dataSource);
    }

    /**
     * 创建标准化遥测批消费者。
     *
     * @param latestValues Redis 实时最新值仓储
     * @param batchWriter  TimescaleDB 历史批写器
     * @param realtimePublisher 实时遥测发布器
     * @param sequenceGapObserver 设备序列缺口观察器
     * @param qualityPolicyRepository 数据质量策略批量读取仓储
     * @param qualityEvaluator 属性级数据质量判定器
     * @param qualityEvidenceMetrics 已提交质量证据指标观察器
     * @param alarmPolicyRepository 告警策略批量读取仓储
     * @param alarmOutboxEventObserver 已提交告警 Outbox 精确唤醒器
     * @return 批消息处理器
     */
    @Bean
    public NormalizedTelemetryConsumer normalizedTelemetryConsumer(
            LatestValueRepository latestValues,
            TelemetryBatchWriter batchWriter,
            ConfirmedRealtimePublisher realtimePublisher,
            TelemetrySequenceGapObserver sequenceGapObserver,
            DataQualityPolicyRepository qualityPolicyRepository,
            DataQualityEvaluator qualityEvaluator,
            QualityEvidenceMetrics qualityEvidenceMetrics,
            AlarmPolicyRepository alarmPolicyRepository,
            AlarmOutboxEventObserver alarmOutboxEventObserver) {
        return new NormalizedTelemetryConsumer(
                latestValues,
                batchWriter,
                realtimePublisher,
                sequenceGapObserver,
                qualityPolicyRepository,
                qualityEvaluator,
                qualityEvidenceMetrics,
                alarmPolicyRepository,
                alarmOutboxEventObserver
        );
    }

    /**
     * 创建持久化设备序列缺口指标观察器。
     *
     * @param meterRegistry Micrometer 指标注册表
     * @return 遥测序列缺口观察器
     */
    @Bean
    public TelemetrySequenceGapObserver telemetrySequenceGapObserver(
            MeterRegistry meterRegistry
    ) {
        return new TelemetrySequenceGapMetrics(meterRegistry);
    }

    /** 创建按批次读取最后正确数据质量策略的本地仓储。 */
    @Bean
    public DataQualityPolicyRepository dataQualityPolicyRepository(DataSource dataSource) {
        return new DataQualityPolicyRepository(dataSource);
    }

    /** 创建按批次读取最后正确告警策略的本地仓储。 */
    @Bean
    public AlarmPolicyRepository alarmPolicyRepository(DataSource dataSource) {
        return new AlarmPolicyRepository(dataSource);
    }

    /** 创建无状态属性级数据质量判定器。 */
    @Bean
    public DataQualityEvaluator dataQualityEvaluator() {
        return new DataQualityEvaluator();
    }

    /** 创建只含规则和结果低基数标签的质量命中指标。 */
    @Bean
    public QualityEvidenceMetrics qualityEvidenceMetrics(MeterRegistry meterRegistry) {
        return new QualityEvidenceMetrics(meterRegistry);
    }

    /**
     * 创建等待 Broker 确认的实时遥测发布器。
     *
     * @param telemetryConnectionFactory 平台 vhost 连接
     * @param properties 遥测 RabbitMQ 参数
     * @return 实时事件发布器
     */
    @Bean
    public ConfirmedRealtimePublisher confirmedRealtimePublisher(
            CachingConnectionFactory telemetryConnectionFactory,
            TelemetryRabbitProperties properties
    ) {
        return new ConfirmedRealtimePublisher(
                new RabbitTemplate(telemetryConnectionFactory),
                positive(properties.resolvedConfirmTimeoutMillis(), "confirmTimeoutMillis")
        );
    }

    /** 创建使用原子 SQL 精确认领告警事件的 Outbox 仓储。 */
    @Bean
    public TelemetryOutboxRepository telemetryOutboxRepository(DataSource dataSource) {
        return new TelemetryOutboxRepository(dataSource);
    }

    /** 创建不携带业务标识标签的告警 Outbox 积压指标。 */
    @Bean
    public AlarmMetrics alarmMetrics(
            MeterRegistry registry,
            TelemetryOutboxRepository repository
    ) {
        return new AlarmMetrics(registry, repository);
    }

    /** 创建只向业务交换机发送告警白名单事件的确认发布器。 */
    @Bean
    public RabbitAlarmOutboxPublisher rabbitAlarmOutboxPublisher(
            CachingConnectionFactory telemetryConnectionFactory,
            TelemetryRabbitProperties properties
    ) {
        RabbitTemplate template = new RabbitTemplate(telemetryConnectionFactory);
        template.setMandatory(true);
        return new RabbitAlarmOutboxPublisher(
                template,
                positive(properties.resolvedConfirmTimeoutMillis(), "confirmTimeoutMillis")
        );
    }

    /** 创建只负责已知事件标识重试的单线程调度器。 */
    @Bean(name = "telemetryOutboxTaskScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler telemetryOutboxTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("telemetry-outbox-retry-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    /** 创建限制并发和排队容量的 Outbox 发布执行器，避免告警风暴耗尽下游资源。 */
    @Bean(name = "telemetryOutboxExecutor", destroyMethod = "close")
    public ExecutorService telemetryOutboxExecutor(TelemetryRabbitProperties properties) {
        TelemetryRabbitProperties.Outbox outbox = properties.resolvedOutbox();
        int concurrency = positive(
                outbox.resolvedDispatchConcurrency(), "outbox.dispatchConcurrency");
        int queueCapacity = positive(
                outbox.resolvedDispatchQueueCapacity(), "outbox.dispatchQueueCapacity");
        return new ThreadPoolExecutor(
                concurrency,
                concurrency,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                Thread.ofPlatform().name("telemetry-outbox-dispatch-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** 创建只调度一个已知告警事件标识的进程内重试器。 */
    @Bean
    public TelemetryOutboxRelay.RetryScheduler telemetryOutboxRetryScheduler(
            @Qualifier("telemetryOutboxTaskScheduler") ThreadPoolTaskScheduler scheduler,
            ObjectProvider<TelemetryOutboxRelay> relayProvider
    ) {
        return (eventId, retryAt) -> scheduler.schedule(
                () -> relayProvider.getObject().publishById(eventId), retryAt);
    }

    /** 创建带租约、指数退避和有界恢复的告警 Outbox 中继器。 */
    @Bean
    public TelemetryOutboxRelay telemetryOutboxRelay(
            TelemetryOutboxRepository repository,
            RabbitAlarmOutboxPublisher publisher,
            TelemetryOutboxRelay.RetryScheduler retryScheduler,
            TelemetryRabbitProperties properties
    ) {
        TelemetryRabbitProperties.Outbox outbox = properties.resolvedOutbox();
        return new TelemetryOutboxRelay(
                repository,
                publisher,
                retryScheduler,
                outbox.resolvedInstanceId(),
                outbox.resolvedLeaseDuration(),
                outbox.resolvedMaxAttempts(),
                outbox.resolvedRecoveryLimit(),
                outbox.resolvedRetryBaseDelay(),
                outbox.resolvedRetryMaxDelay(),
                Clock.systemUTC()
        );
    }

    /** 创建事务提交后使用虚拟线程精确发布告警 Outbox 的观察器。 */
    @Bean
    public AlarmOutboxEventObserver alarmOutboxEventObserver(
            TelemetryOutboxRelay relay,
            @Qualifier("telemetryOutboxExecutor") ExecutorService executor
    ) {
        return eventId -> executor.execute(() -> relay.publishById(eventId));
    }

    /** 创建应用启动和 RabbitMQ 重连后的有界恢复协调器。 */
    @Bean
    public TelemetryOutboxRecovery telemetryOutboxRecovery(
            TelemetryOutboxRelay relay,
            CachingConnectionFactory telemetryConnectionFactory,
            @Qualifier("telemetryOutboxExecutor") ExecutorService executor,
            @Qualifier("telemetryOutboxTaskScheduler") ThreadPoolTaskScheduler scheduler,
            TelemetryRabbitProperties properties
    ) {
        return new TelemetryOutboxRecovery(
                relay,
                telemetryConnectionFactory,
                executor,
                scheduler,
                properties.resolvedOutbox().resolvedRecoveryInterval());
    }

    /**
     * 创建访问平台 vhost 的遥测最小权限连接，用于消费标准遥测并发布实时事件。
     *
     * @param properties 遥测 RabbitMQ 参数
     * @return 平台消息消费连接工厂
     */
    @Bean(destroyMethod = "destroy")
    public CachingConnectionFactory telemetryConnectionFactory(TelemetryRabbitProperties properties) {
        TelemetryRabbitProperties.Account account = properties.account();
        if (account == null || account.username() == null || account.username().isBlank()) {
            throw new IllegalArgumentException("遥测 RabbitMQ 服务账号不能为空");
        }
        if (account.passwordFile() == null) {
            throw new IllegalArgumentException("遥测 RabbitMQ 密码文件不能为空");
        }
        CachingConnectionFactory factory = new CachingConnectionFactory(
                properties.resolvedHost(), positive(properties.resolvedPort(), "port")
        );
        factory.setVirtualHost(MineTopology.PLATFORM_VHOST);
        factory.setUsername(account.username());
        factory.setPassword(readSecret(account.passwordFile()));
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        factory.setPublisherReturns(true);
        return factory;
    }

    /**
     * 创建手工确认、消费者侧凑批的 RabbitMQ 监听容器。
     *
     * @param telemetryConnectionFactory 平台 vhost 遥测服务连接
     * @param consumer                    标准化遥测批消费者
     * @param properties                  遥测 RabbitMQ 参数
     * @return 遥测消息监听容器
     */
    @Bean(destroyMethod = "destroy")
    public SimpleMessageListenerContainer telemetryMessageListenerContainer(
            CachingConnectionFactory telemetryConnectionFactory,
            NormalizedTelemetryConsumer consumer,
            TelemetryRabbitProperties properties) {
        int batchSize = positive(properties.resolvedBatchSize(), "batchSize");
        int prefetch = positive(properties.resolvedPrefetch(), "prefetch");
        if (prefetch < batchSize) {
            throw new IllegalArgumentException("prefetch 不能小于 batchSize");
        }
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(
                telemetryConnectionFactory
        );
        container.setQueueNames(MineTopology.TELEMETRY_QUEUE);
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setPrefetchCount(prefetch);
        container.setBatchSize(batchSize);
        container.setConsumerBatchEnabled(true);
        container.setReceiveTimeout(positive(properties.resolvedReceiveTimeoutMillis(), "receiveTimeoutMillis"));
        container.setBatchReceiveTimeout(properties.resolvedReceiveTimeoutMillis());
        container.setDefaultRequeueRejected(true);
        container.setMissingQueuesFatal(true);
        container.setAutoStartup(properties.resolvedAutoStartup());
        container.setMessageListener(consumer);
        return container;
    }

    /** 创建 TimescaleDB 数据质量策略持久镜像仓储。 */
    @Bean
    @ConditionalOnProperty(
            prefix = "mine.telemetry.strategy-distribution", name = "enabled", havingValue = "true")
    public StrategyPolicyRepository strategyPolicyRepository(
            DataSource dataSource,
            AlarmOutboxEventObserver alarmOutboxEventObserver
    ) {
        return new StrategyPolicyRepository(dataSource, alarmOutboxEventObserver);
    }

    /** 创建使用服务令牌拉取不可变快照的控制面客户端。 */
    @Bean
    @ConditionalOnProperty(
            prefix = "mine.telemetry.strategy-distribution", name = "enabled", havingValue = "true")
    public HttpStrategySnapshotSource strategySnapshotSource(
            StrategyDistributionProperties properties
    ) {
        return new HttpStrategySnapshotSource(properties);
    }

    /** 创建分页校验并原子切换本地策略镜像的加载器。 */
    @Bean
    @ConditionalOnProperty(
            prefix = "mine.telemetry.strategy-distribution", name = "enabled", havingValue = "true")
    public StrategySnapshotLoader strategySnapshotLoader(
            StrategyPolicyRepository repository,
            HttpStrategySnapshotSource source,
            StrategyDistributionProperties properties,
            StrategyDistributionMetrics metrics
    ) {
        return new StrategySnapshotLoader(
                repository, source, properties.resolvedPageSize(), metrics);
    }

    /** 创建策略分发最终结果、阶段和耗时的低基数指标。 */
    @Bean
    @ConditionalOnProperty(
            prefix = "mine.telemetry.strategy-distribution", name = "enabled", havingValue = "true")
    public StrategyDistributionMetrics strategyDistributionMetrics(MeterRegistry meterRegistry) {
        return new StrategyDistributionMetrics(meterRegistry);
    }

    /** 创建策略运行变更消息消费者。 */
    @Bean
    @ConditionalOnProperty(
            prefix = "mine.telemetry.strategy-distribution", name = "enabled", havingValue = "true")
    public StrategyRuntimeConsumer strategyRuntimeConsumer(
            StrategySnapshotLoader loader,
            StrategyDistributionMetrics metrics
    ) {
        return new StrategyRuntimeConsumer(loader, metrics);
    }

    /**
     * 创建单消息手工确认的策略监听容器，按产品修订顺序逐条原子应用。
     */
    @Bean(destroyMethod = "destroy")
    @ConditionalOnProperty(
            prefix = "mine.telemetry.strategy-distribution", name = "enabled", havingValue = "true")
    public SimpleMessageListenerContainer strategyMessageListenerContainer(
            CachingConnectionFactory telemetryConnectionFactory,
            StrategyRuntimeConsumer consumer,
            TelemetryRabbitProperties properties
    ) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(
                telemetryConnectionFactory);
        container.setQueueNames(MineTopology.TELEMETRY_STRATEGY_QUEUE);
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setPrefetchCount(1);
        container.setConcurrentConsumers(1);
        container.setMaxConcurrentConsumers(1);
        container.setDefaultRequeueRejected(true);
        container.setMissingQueuesFatal(true);
        container.setAutoStartup(properties.resolvedAutoStartup());
        container.setMessageListener(consumer);
        return container;
    }

    /** 创建定位快照固定阶段和最终结果的低基数指标。 */
    @Bean
    @ConditionalOnProperty(
            prefix = "mine.telemetry.location-distribution", name = "enabled", havingValue = "true")
    public LocationSnapshotMetrics locationSnapshotMetrics(MeterRegistry meterRegistry) {
        return new LocationSnapshotMetrics(meterRegistry);
    }

    /** 创建版本化坐标转换算法注册表。 */
    @Bean
    @ConditionalOnProperty(
            prefix = "mine.telemetry.location-distribution", name = "enabled", havingValue = "true")
    public CoordinateTransformRegistry coordinateTransformRegistry() {
        return new CoordinateTransformRegistry();
    }

    /** 创建并从数据库恢复最后正确定位运行快照。 */
    @Bean
    @DependsOnDatabaseInitialization
    @ConditionalOnProperty(
            prefix = "mine.telemetry.location-distribution", name = "enabled", havingValue = "true")
    public LocationRuntimeRegistry locationRuntimeRegistry(
            DataSource dataSource,
            CoordinateTransformRegistry transformRegistry,
            LocationSnapshotMetrics metrics
    ) {
        LocationRuntimeRegistry registry = new LocationRuntimeRegistry(
                dataSource, transformRegistry);
        try {
            registry.restore();
            metrics.stage(LocationSnapshotMetrics.Stage.RESTORE,
                    LocationSnapshotMetrics.Result.SUCCESS);
        } catch (RuntimeException exception) {
            metrics.stage(LocationSnapshotMetrics.Stage.RESTORE,
                    LocationSnapshotMetrics.Result.FAILURE);
            throw exception;
        }
        return registry;
    }

    /** 创建使用服务令牌拉取定位运行快照的控制面客户端。 */
    @Bean
    @ConditionalOnProperty(
            prefix = "mine.telemetry.location-distribution", name = "enabled", havingValue = "true")
    public HttpLocationSnapshotSource locationSnapshotSource(
            LocationDistributionProperties properties
    ) {
        return new HttpLocationSnapshotSource(properties);
    }

    /** 创建完整校验并原子激活定位运行快照的加载器。 */
    @Bean
    @ConditionalOnProperty(
            prefix = "mine.telemetry.location-distribution", name = "enabled", havingValue = "true")
    public LocationSnapshotLoader locationSnapshotLoader(
            LocationRuntimeRegistry registry,
            HttpLocationSnapshotSource source,
            LocationDistributionProperties properties,
            LocationSnapshotMetrics metrics
    ) {
        return new LocationSnapshotLoader(
                registry, source, properties.resolvedPageSize(), metrics);
    }

    /** 创建定位运行变更消息消费者。 */
    @Bean
    @ConditionalOnProperty(
            prefix = "mine.telemetry.location-distribution", name = "enabled", havingValue = "true")
    public LocationSnapshotConsumer locationSnapshotConsumer(
            LocationSnapshotLoader loader,
            LocationSnapshotMetrics metrics
    ) {
        return new LocationSnapshotConsumer(loader, metrics);
    }

    /** 创建单消息手工确认的定位运行快照监听容器。 */
    @Bean(destroyMethod = "destroy")
    @ConditionalOnProperty(
            prefix = "mine.telemetry.location-distribution", name = "enabled", havingValue = "true")
    public SimpleMessageListenerContainer locationSnapshotMessageListenerContainer(
            CachingConnectionFactory telemetryConnectionFactory,
            LocationSnapshotConsumer consumer,
            TelemetryRabbitProperties properties
    ) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(
                telemetryConnectionFactory);
        container.setQueueNames(MineTopology.TELEMETRY_LOCATION_CONFIG_QUEUE);
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setPrefetchCount(1);
        container.setConcurrentConsumers(1);
        container.setMaxConcurrentConsumers(1);
        container.setDefaultRequeueRejected(true);
        container.setMissingQueuesFatal(true);
        container.setAutoStartup(properties.resolvedAutoStartup());
        container.setMessageListener(consumer);
        return container;
    }

    /** 创建固定时间与精度阈值的定位质量判定器。 */
    @Bean
    @ConditionalOnProperty(
            prefix = "mine.telemetry.location-distribution", name = "enabled", havingValue = "true")
    public LocationQualityEvaluator locationQualityEvaluator() {
        return new LocationQualityEvaluator();
    }

    /** 创建位置幂等、历史、隔离和实时 Outbox 的事务写入器。 */
    @Bean
    @ConditionalOnProperty(
            prefix = "mine.telemetry.location-distribution", name = "enabled", havingValue = "true")
    public LocationFactWriter locationFactWriter(
            DataSource dataSource,
            LocationRuntimeRegistry registry,
            CoordinateTransformRegistry transformRegistry,
            LocationQualityEvaluator qualityEvaluator
    ) {
        return new LocationFactWriter(
                dataSource, registry, transformRegistry, qualityEvaluator);
    }

    /** 创建带顺序保护且可从可靠 Outbox 重建的 Redis 当前位置缓存。 */
    @Bean
    @ConditionalOnProperty(
            prefix = "mine.telemetry.location-distribution", name = "enabled", havingValue = "true")
    public LocationLatestCache locationLatestCache(
            StringRedisTemplate redisTemplate,
            DataSource dataSource
    ) {
        return new LocationLatestCache(redisTemplate, dataSource);
    }

    /** 创建只向实时交换机发布矿区位置白名单事件的确认发布器。 */
    @Bean
    @ConditionalOnProperty(
            prefix = "mine.telemetry.location-distribution", name = "enabled", havingValue = "true")
    public ConfirmedRealtimeLocationPublisher confirmedRealtimeLocationPublisher(
            CachingConnectionFactory telemetryConnectionFactory,
            TelemetryRabbitProperties properties
    ) {
        RabbitTemplate template = new RabbitTemplate(telemetryConnectionFactory);
        template.setMandatory(true);
        return new ConfirmedRealtimeLocationPublisher(
                template,
                positive(properties.resolvedConfirmTimeoutMillis(), "confirmTimeoutMillis"));
    }

    /** 创建位置专用重试和周期恢复调度器。 */
    @Bean(name = "locationOutboxTaskScheduler", destroyMethod = "shutdown")
    @ConditionalOnProperty(
            prefix = "mine.telemetry.location-distribution", name = "enabled", havingValue = "true")
    public ThreadPoolTaskScheduler locationOutboxTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("location-recovery-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    /** 创建与告警隔离的高频位置 Outbox 有界执行器。 */
    @Bean(name = "locationOutboxExecutor", destroyMethod = "close")
    @ConditionalOnProperty(
            prefix = "mine.telemetry.location-distribution", name = "enabled", havingValue = "true")
    public ExecutorService locationOutboxExecutor(TelemetryRabbitProperties properties) {
        TelemetryRabbitProperties.Outbox outbox = properties
                .resolvedLocation().resolvedOutbox();
        int concurrency = positive(
                outbox.resolvedDispatchConcurrency(), "location.outbox.dispatchConcurrency");
        int capacity = positive(
                outbox.resolvedDispatchQueueCapacity(), "location.outbox.queueCapacity");
        return new ThreadPoolExecutor(
                concurrency, concurrency, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity),
                Thread.ofPlatform().name("location-outbox-dispatch-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** 创建只调度一个已知位置事件标识的进程内重试器。 */
    @Bean
    @ConditionalOnProperty(
            prefix = "mine.telemetry.location-distribution", name = "enabled", havingValue = "true")
    public LocationRealtimeOutboxRelay.RetryScheduler locationOutboxRetryScheduler(
            @Qualifier("locationOutboxTaskScheduler") ThreadPoolTaskScheduler scheduler,
            ObjectProvider<LocationRealtimeOutboxRelay> relayProvider
    ) {
        return (eventId, retryAt) -> scheduler.schedule(
                () -> relayProvider.getObject().publishById(eventId), retryAt);
    }

    /** 创建带数据库租约、指数退避和有界恢复的位置实时中继器。 */
    @Bean
    @ConditionalOnProperty(
            prefix = "mine.telemetry.location-distribution", name = "enabled", havingValue = "true")
    public LocationRealtimeOutboxRelay locationRealtimeOutboxRelay(
            DataSource dataSource,
            ConfirmedRealtimeLocationPublisher publisher,
            LocationRealtimeOutboxRelay.RetryScheduler retryScheduler,
            TelemetryRabbitProperties properties
    ) {
        TelemetryRabbitProperties.Outbox outbox = properties
                .resolvedLocation().resolvedOutbox();
        return new LocationRealtimeOutboxRelay(
                dataSource, publisher, retryScheduler,
                outbox.resolvedInstanceId(), outbox.resolvedLeaseDuration(),
                outbox.resolvedMaxAttempts(), outbox.resolvedRecoveryLimit(),
                outbox.resolvedRetryBaseDelay(), outbox.resolvedRetryMaxDelay(),
                Clock.systemUTC());
    }

    /** 创建事务提交后在独立有界执行器中精确发布位置事件的观察器。 */
    @Bean
    @ConditionalOnProperty(
            prefix = "mine.telemetry.location-distribution", name = "enabled", havingValue = "true")
    public LocationObservationConsumer.CommittedEventObserver locationOutboxEventObserver(
            LocationRealtimeOutboxRelay relay,
            @Qualifier("locationOutboxExecutor") ExecutorService executor
    ) {
        return eventId -> executor.execute(() -> relay.publishById(eventId));
    }

    /** 创建位置观测事务消费者。 */
    @Bean
    @ConditionalOnProperty(
            prefix = "mine.telemetry.location-distribution", name = "enabled", havingValue = "true")
    public LocationObservationConsumer locationObservationConsumer(
            LocationFactWriter writer,
            LocationLatestCache cache,
            LocationObservationConsumer.CommittedEventObserver observer
    ) {
        return new LocationObservationConsumer(writer, cache, observer);
    }

    /** 创建位置独立队列的单消息手工确认监听容器。 */
    @Bean(destroyMethod = "destroy")
    @ConditionalOnProperty(
            prefix = "mine.telemetry.location-distribution", name = "enabled", havingValue = "true")
    public SimpleMessageListenerContainer locationObservationMessageListenerContainer(
            CachingConnectionFactory telemetryConnectionFactory,
            LocationObservationConsumer consumer,
            TelemetryRabbitProperties properties
    ) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(
                telemetryConnectionFactory);
        container.setQueueNames(MineTopology.LOCATION_QUEUE);
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setPrefetchCount(positive(
                properties.resolvedLocation().resolvedPrefetch(), "location.prefetch"));
        container.setConcurrentConsumers(1);
        container.setMaxConcurrentConsumers(1);
        container.setDefaultRequeueRejected(true);
        container.setMissingQueuesFatal(true);
        container.setAutoStartup(properties.resolvedAutoStartup());
        container.setMessageListener(consumer);
        return container;
    }

    /** 创建启动、重连和固定周期的位置 Outbox 与缓存恢复协调器。 */
    @Bean
    @ConditionalOnProperty(
            prefix = "mine.telemetry.location-distribution", name = "enabled", havingValue = "true")
    public LocationRecoveryCoordinator locationRecoveryCoordinator(
            LocationRealtimeOutboxRelay relay,
            LocationLatestCache cache,
            CachingConnectionFactory telemetryConnectionFactory,
            @Qualifier("locationOutboxExecutor") ExecutorService executor,
            @Qualifier("locationOutboxTaskScheduler") ThreadPoolTaskScheduler scheduler,
            TelemetryRabbitProperties properties
    ) {
        TelemetryRabbitProperties.Location location = properties.resolvedLocation();
        return new LocationRecoveryCoordinator(
                relay, cache, telemetryConnectionFactory, executor, scheduler,
                location.resolvedOutbox().resolvedRecoveryInterval(),
                location.resolvedCacheRecoveryInterval(),
                positive(location.resolvedCacheRecoverySiteLimit(),
                        "location.cacheRecoverySiteLimit"),
                positive(location.resolvedCacheRecoveryTagLimit(),
                        "location.cacheRecoveryTagLimit"));
    }

    private String readSecret(java.nio.file.Path path) {
        try {
            String value = Files.readString(path).strip();
            if (value.isEmpty()) {
                throw new IllegalArgumentException("遥测 RabbitMQ 密码文件不能为空");
            }
            return value;
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法读取遥测 RabbitMQ 密码文件: " + path, exception);
        }
    }

    private int positive(int value, String field) {
        if (value < 1) {
            throw new IllegalArgumentException(field + " 必须大于 0");
        }
        return value;
    }

    private long positive(long value, String field) {
        if (value < 1L) {
            throw new IllegalArgumentException(field + " 必须大于 0");
        }
        return value;
    }
}
