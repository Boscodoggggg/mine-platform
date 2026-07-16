package com.bosco.mine.gateway.configuration;

import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.configuration.ConfigReleasePayload;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;

import java.util.Objects;
import java.util.regex.Pattern;

import static com.bosco.mine.gateway.configuration.ConfigurationDistributionMetrics.Outcome;
import static com.bosco.mine.gateway.configuration.ConfigurationDistributionMetrics.Stage;
import static com.bosco.mine.gateway.configuration.ConfigurationDistributionMetrics.StageResult;

/**
 * 消费轻量配置事件，拉取、校验并原子激活完整快照后手工确认消息。
 */
public final class ConfigurationReleaseConsumer implements ChannelAwareMessageListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigurationReleaseConsumer.class);
    private static final int MAX_FAILURE_SUMMARY_LENGTH = 500;
    private static final Pattern JSON_SECRET_PATTERN = Pattern.compile(
            "(?i)([\"'](?:token|password|secret|authorization|credential)[\"']\\s*:\\s*[\"'])(.*?)([\"'])");
    private static final Pattern KEY_VALUE_SECRET_PATTERN = Pattern.compile(
            "(?i)(token|password|secret|authorization|credential)(\\s*[:=]\\s*(?:bearer\\s+)?)([^\\s,;&]+)");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("[\\p{Cntrl}\\s]+");

    private final Decoder decoder;
    private final ConfigurationSnapshotClient client;
    private final ProtocolSnapshotRegistry registry;
    private final ConfigurationSnapshotStore snapshotStore;
    private final ConfigurationDistributionMetrics metrics;

    /**
     * 创建配置发布消费者。
     *
     * @param decoder 配置事件解码器
     * @param client 完整快照客户端
     * @param registry 协议快照注册表
     * @param snapshotStore 最后可用配置持久化边界
     */
    public ConfigurationReleaseConsumer(
            Decoder decoder,
            ConfigurationSnapshotClient client,
            ProtocolSnapshotRegistry registry,
            ConfigurationSnapshotStore snapshotStore
    ) {
        this(decoder, client, registry, snapshotStore,
                new ConfigurationDistributionMetrics(new SimpleMeterRegistry()));
    }

    /**
     * 创建带可观测证据的配置发布消费者。
     *
     * @param decoder 配置事件解码器
     * @param client 完整快照客户端
     * @param registry 协议快照注册表
     * @param snapshotStore 最后可用配置持久化边界
     * @param metrics 配置分发低基数指标
     */
    public ConfigurationReleaseConsumer(
            Decoder decoder,
            ConfigurationSnapshotClient client,
            ProtocolSnapshotRegistry registry,
            ConfigurationSnapshotStore snapshotStore,
            ConfigurationDistributionMetrics metrics
    ) {
        this.decoder = Objects.requireNonNull(decoder, "配置事件解码器不能为空");
        this.client = Objects.requireNonNull(client, "配置快照客户端不能为空");
        this.registry = Objects.requireNonNull(registry, "协议快照注册表不能为空");
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "配置快照持久化边界不能为空");
        this.metrics = Objects.requireNonNull(metrics, "配置分发指标不能为空");
    }

    /**
     * 处理配置事件；瞬时拉取失败重投，永久损坏消息进入死信。
     *
     * @param message RabbitMQ 配置消息
     * @param channel 平台消息消费通道
     * @throws Exception RabbitMQ 确认操作失败时抛出
     */
    @Override
    public void onMessage(Message message, Channel channel) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        ConfigurationDistributionMetrics.ProcessingSample sample = metrics.start();
        Stage currentStage = Stage.DECODE;
        ConfigReleasePayload payload = null;
        try {
            MessageEnvelope<ConfigReleasePayload> envelope = decoder.decode(message.getBody());
            payload = envelope.payload();
            metrics.stage(Stage.DECODE, StageResult.SUCCESS);
            currentStage = Stage.FETCH;
            ConfigurationSnapshot snapshot = client.fetch(payload.releaseId());
            metrics.stage(Stage.FETCH, StageResult.SUCCESS);
            currentStage = Stage.VALIDATE;
            validateMatch(payload, snapshot);
            metrics.stage(Stage.VALIDATE, StageResult.SUCCESS);
            currentStage = Stage.ACTIVATE;
            registry.activate(snapshot);
            metrics.stage(Stage.ACTIVATE, StageResult.SUCCESS);
            currentStage = Stage.PERSIST;
            snapshotStore.persist(snapshot);
            metrics.stage(Stage.PERSIST, StageResult.SUCCESS);
            currentStage = Stage.REPORT;
            client.reportLoaded(payload.releaseId(), payload.contentHash());
            metrics.stage(Stage.REPORT, StageResult.SUCCESS);
            metrics.complete(sample, Outcome.LOADED);
            channel.basicAck(deliveryTag, false);
        } catch (InvalidConfigurationMessageException exception) {
            metrics.stage(Stage.DECODE, StageResult.FAILURE);
            metrics.complete(sample, Outcome.INVALID_MESSAGE);
            LOGGER.warn("拒绝不可重试的配置发布消息，原因=消息契约不合法");
            channel.basicReject(deliveryTag, false);
        } catch (InvalidConfigurationSnapshotException exception) {
            metrics.stage(currentStage, StageResult.FAILURE);
            if (payload == null) {
                metrics.complete(sample, Outcome.INVALID_MESSAGE);
                channel.basicReject(deliveryTag, false);
                return;
            }
            try {
                String failureSummary = safeFailureSummary(exception);
                client.reportFailed(payload.releaseId(), failureSummary);
                metrics.stage(Stage.REPORT, StageResult.SUCCESS);
                metrics.complete(sample, Outcome.PERMANENT_FAILURE);
                LOGGER.warn("配置发布永久失败并已回报节点证据: {}", failureSummary);
                channel.basicReject(deliveryTag, false);
            } catch (RuntimeException reportException) {
                metrics.stage(Stage.REPORT, StageResult.FAILURE);
                metrics.complete(sample, Outcome.TRANSIENT_FAILURE);
                LOGGER.warn("配置失败状态暂时无法回报，消息将重新投递，异常类型={}",
                        reportException.getClass().getSimpleName());
                channel.basicNack(deliveryTag, false, true);
            }
        } catch (ConfigurationFetchException exception) {
            metrics.stage(currentStage, StageResult.FAILURE);
            metrics.complete(sample, Outcome.TRANSIENT_FAILURE);
            LOGGER.warn("配置快照暂时无法拉取，消息将重新投递: {}", exception.getMessage());
            channel.basicNack(deliveryTag, false, true);
        } catch (RuntimeException exception) {
            metrics.stage(currentStage, StageResult.FAILURE);
            metrics.complete(sample, Outcome.TRANSIENT_FAILURE);
            LOGGER.error("配置发布处理出现瞬时失败，消息将重新投递", exception);
            channel.basicNack(deliveryTag, false, true);
        }
    }

    private void validateMatch(ConfigReleasePayload payload, ConfigurationSnapshot snapshot) {
        if (!payload.releaseId().equals(snapshot.releaseId())
                || !payload.packageId().equals(snapshot.packageId())
                || !payload.productId().equals(snapshot.productId())
                || payload.releaseSequence() != snapshot.releaseSequence()
                || !payload.contentHash().equals(snapshot.contentHash())) {
            throw new InvalidConfigurationSnapshotException("配置事件与管理服务快照定位信息不一致");
        }
    }

    private String safeFailureSummary(InvalidConfigurationSnapshotException exception) {
        String message = exception.getMessage();
        String normalized = WHITESPACE_PATTERN.matcher(
                message == null || message.isBlank() ? "配置快照不合法" : message
        ).replaceAll(" ").strip();
        String jsonRedacted = JSON_SECRET_PATTERN.matcher(normalized)
                .replaceAll("$1[REDACTED]$3");
        String redacted = KEY_VALUE_SECRET_PATTERN.matcher(jsonRedacted)
                .replaceAll("$1$2[REDACTED]");
        String summary = "SNAPSHOT_INVALID: " + redacted;
        if (summary.length() <= MAX_FAILURE_SUMMARY_LENGTH) return summary;
        return summary.substring(0, MAX_FAILURE_SUMMARY_LENGTH - 3) + "...";
    }

    /** 配置发布信封解码边界。 */
    @FunctionalInterface
    public interface Decoder {
        /**
         * 解码配置发布消息。
         *
         * @param body 消息 JSON
         * @return 配置发布信封
         */
        MessageEnvelope<ConfigReleasePayload> decode(byte[] body);
    }
}
