package com.bosco.mine.server.modules.configuration.service;

import cn.dev33.satoken.stp.StpUtil;
import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.protocol.engine.json.FieldTransform;
import com.bosco.mine.protocol.engine.json.MqttJsonExecutionPlan;
import com.bosco.mine.protocol.engine.json.MqttJsonProtocolCompiler;
import com.bosco.mine.protocol.engine.json.MqttJsonProtocolDefinition;
import com.bosco.mine.protocol.engine.sample.ProtocolSample;
import com.bosco.mine.protocol.engine.sample.ProtocolSampleRunner;
import com.bosco.mine.protocol.engine.snapshot.ProtocolSnapshotHasher;
import com.bosco.mine.server.modules.command.outbox.OutboxCommitNotifier;
import com.bosco.mine.server.modules.configuration.repository.ProtocolConfigurationMapper;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.MDC;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 编排协议黄金样本、配置包发布、回滚和运行节点快照用例。
 */
@Service
public class ProtocolConfigurationService {

    private static final ErrorCode NOT_FOUND =
            new ErrorCode("PROTOCOL-CONFIG-404-001", "协议配置或发布不存在");
    private static final ErrorCode SAMPLE_EDIT_FORBIDDEN =
            new ErrorCode("PROTOCOL-SAMPLE-422-001", "只有协议草稿允许修改黄金样本");
    private static final ErrorCode SAMPLE_INVALID =
            new ErrorCode("PROTOCOL-SAMPLE-422-002", "协议黄金样本不合法");
    private static final ErrorCode VERSION_CONFLICT =
            new ErrorCode("PROTOCOL-SAMPLE-409-001", "协议草稿已被其他管理员修改");
    private static final ErrorCode DEFINITION_INVALID =
            new ErrorCode("PROTOCOL-CONFIG-422-001", "协议定义无法编译为安全执行计划");
    private static final ErrorCode RELEASE_GATE_FAILED =
            new ErrorCode("CONFIG-RELEASE-422-001", "黄金样本未全部通过，禁止发布配置包");
    private static final ErrorCode RELEASE_INVALID =
            new ErrorCode("CONFIG-RELEASE-422-002", "配置发布参数或引用版本不合法");

    private static final int MAX_SAMPLES = 200;
    private static final int MAX_SAMPLE_BYTES = 64 * 1024;
    private static final Pattern NODE_ID = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");
    private static final Pattern HASH = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final ProtocolConfigurationMapper mapper;
    private final OperationAuditService auditService;
    private final ObjectMapper objectMapper;
    private final OutboxCommitNotifier commitNotifier;
    private final MqttJsonProtocolCompiler compiler = new MqttJsonProtocolCompiler();
    private final ProtocolSampleRunner sampleRunner = new ProtocolSampleRunner();
    private final ProtocolSnapshotHasher snapshotHasher = new ProtocolSnapshotHasher();
    private final IdGenerator idGenerator = IdGenerator.timeOrdered();

    /**
     * 创建协议配置服务。
     *
     * @param mapper 配置控制面数据接口
     * @param auditService 操作审计服务
     * @param objectMapper JSON 序列化器
     * @param commitNotifier Outbox 事务提交通知器
     */
    public ProtocolConfigurationService(
            ProtocolConfigurationMapper mapper,
            OperationAuditService auditService,
            ObjectMapper objectMapper,
            OutboxCommitNotifier commitNotifier
    ) {
        this.mapper = mapper;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.commitNotifier = commitNotifier;
    }

    /**
     * 原子替换协议草稿的全部黄金样本。
     *
     * @param mappingVersionId 协议映射版本标识
     * @param version 乐观锁版本
     * @param samples 新样本集合
     * @return 保存后的样本
     */
    @Transactional
    public List<SampleView> replaceSamples(
            UUID mappingVersionId,
            long version,
            List<SampleCommand> samples
    ) {
        ProtocolConfigurationMapper.ProtocolDefinitionRecord definition = requireDefinition(mappingVersionId);
        if (!"DRAFT".equals(definition.status())) {
            throw new MineException(SAMPLE_EDIT_FORBIDDEN, Map.of("mappingVersionId", mappingVersionId));
        }
        validateSamples(samples);
        UUID operatorId = currentOperatorId();
        if (mapper.touchDraft(mappingVersionId, version, operatorId) == 0) {
            throw new MineException(VERSION_CONFLICT, Map.of("mappingVersionId", mappingVersionId));
        }
        mapper.deleteSamples(mappingVersionId);
        for (SampleCommand sample : samples) {
            mapper.insertSample(
                    idGenerator.next(), mappingVersionId, sample.name().strip(), sample.rawPayloadJson(),
                    sample.expectedSuccess(), sample.expectedPropertiesJson(),
                    normalize(sample.expectedErrorCode()), sample.sortOrder(), operatorId
            );
        }
        auditService.recordSuccess(
                "PROTOCOL", "REPLACE_SAMPLES", "device:protocol:sample:edit",
                "PROTOCOL_VERSION", mappingVersionId,
                Map.of(), Map.of("sampleCount", samples.size())
        );
        return mapper.findSamples(mappingVersionId).stream().map(SampleView::from).toList();
    }

    /**
     * 查询协议映射版本的全部黄金样本。
     *
     * @param mappingVersionId 协议映射版本标识
     * @return 按展示顺序排列的样本
     */
    @Transactional(readOnly = true)
    public List<SampleView> findSamples(UUID mappingVersionId) {
        mapper.findDefinition(mappingVersionId)
                .orElseThrow(() -> new MineException(NOT_FOUND, Map.of("mappingVersionId", mappingVersionId)));
        return mapper.findSamples(mappingVersionId).stream().map(SampleView::from).toList();
    }

    /**
     * 使用共享协议引擎回放当前版本的全部黄金样本。
     *
     * @param mappingVersionId 协议映射版本标识
     * @return 不可变测试运行结果
     */
    @Transactional
    public TestRunView test(UUID mappingVersionId) {
        Bundle bundle = loadBundle(mappingVersionId, null, false);
        return executeGate(bundle);
    }

    /**
     * 查询产品最近的配置发布历史。
     *
     * @param productId 产品标识
     * @param limit 最大返回数量
     * @return 按发布序号倒序排列的发布摘要
     */
    @Transactional(readOnly = true)
    public List<ReleaseSummaryView> findReleases(UUID productId, int limit) {
        if (productId == null || limit < 1 || limit > 100) {
            throw new MineException(RELEASE_INVALID, Map.of("field", "limit"));
        }
        return mapper.findReleaseSummaries(productId, limit).stream()
                .map(ReleaseSummaryView::from)
                .toList();
    }

    /**
     * 查询指定发布及运行节点加载状态。
     *
     * @param releaseId 发布标识
     * @return 发布详情
     */
    @Transactional(readOnly = true)
    public ReleaseDetailView findRelease(UUID releaseId) {
        ProtocolConfigurationMapper.ReleaseSummaryRecord release = mapper.findReleaseSummary(releaseId)
                .orElseThrow(() -> new MineException(NOT_FOUND, Map.of("releaseId", releaseId)));
        return new ReleaseDetailView(
                ReleaseSummaryView.from(release),
                mapper.findNodeStatuses(releaseId).stream().map(NodeStatusView::from).toList()
        );
    }

    /**
     * 创建配置包并发布到产品范围。
     *
     * @param command 发布参数
     * @return 发布事实
     */
    @Transactional
    public ReleaseView publish(PublishCommand command) {
        validatePublish(command);
        UUID operatorId = currentOperatorId();
        mapper.lockProduct(command.productId());
        Optional<ProtocolConfigurationMapper.ReleaseRecord> existing =
                mapper.findReleaseByIdempotency(operatorId, command.idempotencyKey());
        if (existing.isPresent()) return ReleaseView.from(existing.get());

        Bundle bundle = loadBundle(command.mappingVersionId(), command.productId(), true);
        TestRunView gate = executeGate(bundle);
        if (!gate.passed()) {
            throw new MineException(
                    RELEASE_GATE_FAILED,
                    Map.of("testRunId", gate.id(), "failedSamples", gate.failedSamples())
            );
        }

        String snapshotJson = writeJson(Map.of(
                "schemaVersion", 1,
                "productId", bundle.definition().productId(),
                "productKey", bundle.definition().productKey(),
                "modelVersionId", bundle.model().id(),
                "protocolMappingVersionId", bundle.definition().mappingVersionId(),
                "definition", bundle.compiledDefinition()
        ));
        String contentHash = snapshotHasher.sha256(snapshotJson);
        ProtocolConfigurationMapper.PackageRecord configPackage = mapper
                .findPackageByHash(command.productId(), contentHash)
                .orElseGet(() -> createPackage(bundle, snapshotJson, contentHash, command.reason(), operatorId));
        return createRelease(
                command.productId(), configPackage.id(), contentHash, snapshotJson,
                "PUBLISH", null, command.reason(), command.idempotencyKey(), operatorId
        );
    }

    /**
     * 选择历史发布的配置包并创建更高序号的新回滚发布。
     *
     * @param historicalReleaseId 历史发布标识
     * @param reason 回滚原因
     * @param idempotencyKey 幂等键
     * @return 新发布事实
     */
    @Transactional
    public ReleaseView rollback(UUID historicalReleaseId, String reason, String idempotencyKey) {
        requireText(reason, "回滚原因");
        requireText(idempotencyKey, "幂等键", 128);
        UUID operatorId = currentOperatorId();
        Optional<ProtocolConfigurationMapper.ReleaseRecord> existing =
                mapper.findReleaseByIdempotency(operatorId, idempotencyKey);
        if (existing.isPresent()) return ReleaseView.from(existing.get());
        ProtocolConfigurationMapper.ReleaseRecord historical = mapper.findRelease(historicalReleaseId)
                .orElseThrow(() -> new MineException(NOT_FOUND, Map.of("releaseId", historicalReleaseId)));
        mapper.lockProduct(historical.productId());
        return createRelease(
                historical.productId(), historical.packageId(), historical.contentHash(), historical.snapshotJson(),
                "ROLLBACK", historicalReleaseId, reason, idempotencyKey, operatorId
        );
    }

    /**
     * 读取运行节点需要的完整非敏感快照。
     *
     * @param releaseId 发布标识
     * @return 发布及快照
     */
    @Transactional(readOnly = true)
    public ReleaseSnapshot findSnapshot(UUID releaseId) {
        ProtocolConfigurationMapper.ReleaseRecord release = mapper.findRelease(releaseId)
                .orElseThrow(() -> new MineException(NOT_FOUND, Map.of("releaseId", releaseId)));
        return new ReleaseSnapshot(
                release.id(), release.productId(), release.packageId(), release.releaseSequence(),
                release.contentHash(), release.snapshotJson()
        );
    }

    /**
     * 保存运行节点对指定配置发布的加载结果。
     *
     * @param releaseId 配置发布标识
     * @param command 节点状态参数
     */
    @Transactional
    public void reportNodeStatus(UUID releaseId, NodeStatusCommand command) {
        ProtocolConfigurationMapper.ReleaseRecord release = mapper.findRelease(releaseId)
                .orElseThrow(() -> new MineException(NOT_FOUND, Map.of("releaseId", releaseId)));
        boolean valid = command != null
                && command.nodeId() != null && NODE_ID.matcher(command.nodeId()).matches()
                && Set.of("GATEWAY", "TELEMETRY", "SERVER").contains(command.nodeType())
                && Set.of("LOADED", "FAILED").contains(command.status())
                && command.message() != null && !command.message().isBlank()
                && command.message().length() <= 500
                && (command.actualHash() == null || HASH.matcher(command.actualHash()).matches());
        if (!valid || "LOADED".equals(command.status())
                && !release.contentHash().equals(command.actualHash())) {
            throw new MineException(RELEASE_INVALID, Map.of("releaseId", releaseId));
        }
        mapper.upsertNodeStatus(
                releaseId, command.nodeId(), command.nodeType(), command.status(),
                command.actualHash(), command.message().strip()
        );
    }

    private ProtocolConfigurationMapper.PackageRecord createPackage(
            Bundle bundle,
            String snapshotJson,
            String contentHash,
            String reason,
            UUID operatorId
    ) {
        UUID packageId = idGenerator.next();
        long packageSequence = mapper.nextPackageSequence(bundle.definition().productId());
        mapper.insertPackage(
                packageId, bundle.definition().productId(), packageSequence, bundle.model().id(),
                bundle.definition().mappingVersionId(), contentHash, snapshotJson, reason, operatorId
        );
        return new ProtocolConfigurationMapper.PackageRecord(
                packageId, bundle.definition().productId(), packageSequence, bundle.model().id(),
                bundle.definition().mappingVersionId(), contentHash, snapshotJson
        );
    }

    private ReleaseView createRelease(
            UUID productId,
            UUID packageId,
            String contentHash,
            String snapshotJson,
            String action,
            UUID rollbackOfReleaseId,
            String reason,
            String idempotencyKey,
            UUID operatorId
    ) {
        UUID releaseId = idGenerator.next();
        long releaseSequence = mapper.nextReleaseSequence(productId);
        mapper.insertRelease(
                releaseId, productId, packageId, releaseSequence, action,
                rollbackOfReleaseId, reason.strip(), idempotencyKey.strip(), operatorId
        );
        mapper.insertReleaseTarget(idGenerator.next(), releaseId, "PRODUCT", productId);
        UUID eventId = idGenerator.next();
        mapper.insertReleaseOutbox(
                eventId,
                releaseId,
                writeJson(Map.of(
                        "releaseId", releaseId,
                        "packageId", packageId,
                        "productId", productId,
                        "releaseSequence", releaseSequence,
                        "contentHash", contentHash
                )),
                writeJson(Map.of(
                        "partitionKey", productId.toString(),
                        "traceId", currentTraceId(eventId)
                ))
        );
        commitNotifier.notifyAfterCommit(eventId);
        auditService.recordSuccess(
                "CONFIGURATION", action, permission(action), "CONFIG_RELEASE", releaseId,
                Map.of(), Map.of(
                        "productId", productId,
                        "packageId", packageId,
                        "releaseSequence", releaseSequence,
                        "contentHash", contentHash
                )
        );
        return new ReleaseView(
                releaseId, productId, packageId, releaseSequence, action,
                rollbackOfReleaseId, contentHash, snapshotJson
        );
    }

    private String permission(String action) {
        return "ROLLBACK".equals(action)
                ? "device:configuration:rollback"
                : "device:configuration:publish";
    }

    private TestRunView executeGate(Bundle bundle) {
        Instant startedAt = Instant.now();
        List<ProtocolSampleRunner.SampleResult> results = new ArrayList<>();
        for (ProtocolConfigurationMapper.SampleRecord sample : bundle.samples()) {
            String deviceCode = textAt(
                    sample.rawPayloadJson(), bundle.compiledDefinition().metadata().deviceCodePath());
            ProtocolSample protocolSample = new ProtocolSample(
                    sample.name(), sample.rawPayloadJson().getBytes(StandardCharsets.UTF_8),
                    sample.expectedSuccess(), readMap(sample.expectedPropertiesJson()), sample.expectedErrorCode()
            );
            ProtocolSampleRunner.TestReport report = sampleRunner.run(
                    bundle.plan(),
                    new MqttJsonExecutionPlan.TrustedIdentity(
                            bundle.definition().productKey(), deviceCode),
                    List.of(protocolSample)
            );
            results.add(report.results().getFirst());
        }
        int passed = (int) results.stream().filter(ProtocolSampleRunner.SampleResult::passed).count();
        int failed = results.size() - passed;
        UUID runId = idGenerator.next();
        String definitionHash = snapshotHasher.sha256(writeJson(bundle.compiledDefinition()));
        Instant completedAt = Instant.now();
        mapper.insertTestRun(
                runId, bundle.definition().mappingVersionId(), definitionHash,
                failed == 0 ? "PASSED" : "FAILED", results.size(), passed, failed,
                writeJson(Map.of("results", results)), startedAt, completedAt, currentOperatorId()
        );
        return new TestRunView(runId, failed == 0, results.size(), passed, failed, results);
    }

    private Bundle loadBundle(UUID mappingVersionId, UUID expectedProductId, boolean requirePublished) {
        ProtocolConfigurationMapper.ProtocolDefinitionRecord definition = requireDefinition(mappingVersionId);
        if (!"MQTT_JSON".equals(definition.protocolType())
                || requirePublished && !"PUBLISHED".equals(definition.status())
                || expectedProductId != null && !expectedProductId.equals(definition.productId())) {
            throw new MineException(RELEASE_INVALID, Map.of("mappingVersionId", mappingVersionId));
        }
        ProtocolConfigurationMapper.ModelVersionRecord model = mapper.findPublishedModel(definition.productId())
                .orElseThrow(() -> new MineException(
                        DEFINITION_INVALID, Map.of("reason", "产品没有已发布物模型")));
        List<ProtocolConfigurationMapper.SampleRecord> samples = mapper.findSamples(mappingVersionId);
        if (samples.isEmpty()) {
            throw new MineException(SAMPLE_INVALID, Map.of("reason", "至少需要一条黄金样本"));
        }
        try {
            MqttJsonProtocolDefinition compiledDefinition = buildDefinition(
                    definition, mapper.findModelFields(model.id()), mapper.findProtocolFields(mappingVersionId));
            return new Bundle(
                    definition, model, compiledDefinition, compiler.compile(compiledDefinition), samples);
        } catch (IllegalArgumentException exception) {
            throw new MineException(DEFINITION_INVALID, Map.of("reason", safeMessage(exception)));
        }
    }

    private MqttJsonProtocolDefinition buildDefinition(
            ProtocolConfigurationMapper.ProtocolDefinitionRecord definition,
            List<ProtocolConfigurationMapper.ModelFieldRecord> modelFields,
            List<ProtocolConfigurationMapper.ProtocolFieldRecord> protocolFields
    ) {
        MqttJsonProtocolDefinition.MetadataPaths metadata = readJson(
                definition.metadataPathsJson(), MqttJsonProtocolDefinition.MetadataPaths.class);
        List<MqttJsonProtocolDefinition.PropertyDefinition> properties = modelFields.stream()
                .map(field -> new MqttJsonProtocolDefinition.PropertyDefinition(
                        field.identifier(), valueType(field.dataType())))
                .toList();
        List<MqttJsonProtocolDefinition.FieldMapping> mappings = protocolFields.stream()
                .map(field -> new MqttJsonProtocolDefinition.FieldMapping(
                        field.sourcePath(), valueType(field.sourceDataType()), field.targetIdentifier(),
                        transform(field.transformType()), field.scaleFactor(), field.offsetValue(),
                        readMap(field.enumMappingJson()), field.requiredField()
                ))
                .toList();
        return new MqttJsonProtocolDefinition(
                definition.profileKey(), definition.versionNumber(), metadata, properties, mappings);
    }

    private FieldTransform transform(String value) {
        if ("EXPRESSION".equals(value)) {
            throw new IllegalArgumentException("任意表达式不能进入运行配置包");
        }
        return FieldTransform.valueOf(value);
    }

    private MqttJsonProtocolDefinition.ValueType valueType(String value) {
        if ("BINARY".equals(value)) {
            throw new IllegalArgumentException("MQTT JSON 第一批不支持二进制源字段");
        }
        return MqttJsonProtocolDefinition.ValueType.valueOf(value);
    }

    private void validateSamples(List<SampleCommand> samples) {
        if (samples == null || samples.isEmpty() || samples.size() > MAX_SAMPLES) {
            throw new MineException(SAMPLE_INVALID, Map.of("reason", "样本数量必须在 1 到 200 之间"));
        }
        Set<String> names = new HashSet<>();
        for (SampleCommand sample : samples) {
            boolean invalidExpectation = sample.expectedSuccess()
                    ? sample.expectedErrorCode() != null && !sample.expectedErrorCode().isBlank()
                    : sample.expectedErrorCode() == null || sample.expectedErrorCode().isBlank();
            if (sample.name() == null || sample.name().isBlank() || sample.name().length() > 128
                    || !names.add(sample.name().strip()) || sample.sortOrder() < 0
                    || sample.rawPayloadJson() == null
                    || sample.rawPayloadJson().getBytes(StandardCharsets.UTF_8).length > MAX_SAMPLE_BYTES
                    || invalidExpectation) {
                throw new MineException(SAMPLE_INVALID, Map.of("sample", sample.name()));
            }
            requireObjectJson(sample.rawPayloadJson(), "rawPayload");
            requireObjectJson(sample.expectedPropertiesJson(), "expectedProperties");
        }
    }

    private void validatePublish(PublishCommand command) {
        if (command == null || command.productId() == null || command.mappingVersionId() == null) {
            throw new MineException(RELEASE_INVALID, Map.of());
        }
        requireText(command.reason(), "发布原因", 500);
        requireText(command.idempotencyKey(), "幂等键", 128);
    }

    private ProtocolConfigurationMapper.ProtocolDefinitionRecord requireDefinition(UUID mappingVersionId) {
        return mapper.findDefinitionForUpdate(mappingVersionId)
                .orElseThrow(() -> new MineException(NOT_FOUND, Map.of("mappingVersionId", mappingVersionId)));
    }

    private void requireObjectJson(String json, String field) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isObject()) {
                throw new MineException(SAMPLE_INVALID, Map.of("field", field));
            }
        } catch (JacksonException exception) {
            throw new MineException(SAMPLE_INVALID, Map.of("field", field));
        }
    }

    private String textAt(String json, String path) {
        try {
            JsonNode current = objectMapper.readTree(json);
            for (String segment : path.split("\\.")) current = current == null ? null : current.get(segment);
            if (current == null || !current.isString() || current.asString().isBlank()) {
                throw new MineException(SAMPLE_INVALID, Map.of("path", path));
            }
            return current.asString();
        } catch (JacksonException exception) {
            throw new MineException(SAMPLE_INVALID, Map.of("path", path));
        }
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("JSON 对象无法解析", exception);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("协议 JSON 无法解析", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("配置快照 JSON 无法序列化", exception);
        }
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private void requireText(String value, String label) {
        requireText(value, label, 500);
    }

    private void requireText(String value, String label, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new MineException(RELEASE_INVALID, Map.of("field", label));
        }
    }

    private UUID currentOperatorId() {
        try {
            return UUID.fromString(StpUtil.getLoginIdAsString());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private String currentTraceId(UUID eventId) {
        String traceId = MDC.get("traceId");
        return traceId == null || traceId.isBlank() ? eventId.toString() : traceId;
    }

    /** 黄金样本保存参数。 */
    public record SampleCommand(
            String name,
            String rawPayloadJson,
            boolean expectedSuccess,
            String expectedPropertiesJson,
            String expectedErrorCode,
            int sortOrder
    ) {
    }

    /** 黄金样本只读视图。 */
    public record SampleView(
            UUID id,
            String name,
            String rawPayloadJson,
            boolean expectedSuccess,
            String expectedPropertiesJson,
            String expectedErrorCode,
            int sortOrder,
            long version
    ) {
        private static SampleView from(ProtocolConfigurationMapper.SampleRecord record) {
            return new SampleView(
                    record.id(), record.name(), record.rawPayloadJson(), record.expectedSuccess(),
                    record.expectedPropertiesJson(), record.expectedErrorCode(),
                    record.sortOrder(), record.version()
            );
        }
    }

    /** 黄金样本测试结果。 */
    public record TestRunView(
            UUID id,
            boolean passed,
            int totalSamples,
            int passedSamples,
            int failedSamples,
            List<ProtocolSampleRunner.SampleResult> results
    ) {
        /** 冻结逐样本结果。 */
        public TestRunView {
            results = List.copyOf(results);
        }
    }

    /** 配置包发布参数。 */
    public record PublishCommand(
            UUID productId,
            UUID mappingVersionId,
            String reason,
            String idempotencyKey
    ) {
    }

    /** 配置发布视图。 */
    public record ReleaseView(
            UUID id,
            UUID productId,
            UUID packageId,
            long releaseSequence,
            String action,
            UUID rollbackOfReleaseId,
            String contentHash,
            String snapshotJson
    ) {
        private static ReleaseView from(ProtocolConfigurationMapper.ReleaseRecord record) {
            return new ReleaseView(
                    record.id(), record.productId(), record.packageId(), record.releaseSequence(),
                    record.action(), record.rollbackOfReleaseId(), record.contentHash(), record.snapshotJson()
            );
        }
    }

    /** 配置发布管理摘要。 */
    public record ReleaseSummaryView(
            UUID id,
            UUID productId,
            UUID packageId,
            long releaseSequence,
            String action,
            UUID rollbackOfReleaseId,
            String contentHash,
            String reason,
            Instant createdAt
    ) {
        private static ReleaseSummaryView from(ProtocolConfigurationMapper.ReleaseSummaryRecord record) {
            return new ReleaseSummaryView(
                    record.id(), record.productId(), record.packageId(), record.releaseSequence(),
                    record.action(), record.rollbackOfReleaseId(), record.contentHash(),
                    record.reason(), record.createdAt()
            );
        }
    }

    /** 配置发布和节点加载状态详情。 */
    public record ReleaseDetailView(
            ReleaseSummaryView release,
            List<NodeStatusView> nodes
    ) {
        /** 冻结运行节点状态集合。 */
        public ReleaseDetailView {
            nodes = List.copyOf(nodes);
        }
    }

    /** 运行节点加载状态只读视图。 */
    public record NodeStatusView(
            String nodeId,
            String nodeType,
            String status,
            String actualHash,
            String message,
            Instant loadedAt,
            Instant updatedAt
    ) {
        private static NodeStatusView from(ProtocolConfigurationMapper.NodeStatusRecord record) {
            return new NodeStatusView(
                    record.nodeId(), record.nodeType(), record.status(), record.actualHash(),
                    record.message(), record.loadedAt(), record.updatedAt()
            );
        }
    }

    /** 运行节点拉取的发布快照。 */
    public record ReleaseSnapshot(
            UUID releaseId,
            UUID productId,
            UUID packageId,
            long releaseSequence,
            String contentHash,
            String snapshotJson
    ) {
    }

    /** 运行节点配置加载状态参数。 */
    public record NodeStatusCommand(
            String nodeId,
            String nodeType,
            String status,
            String actualHash,
            String message
    ) {
    }

    private record Bundle(
            ProtocolConfigurationMapper.ProtocolDefinitionRecord definition,
            ProtocolConfigurationMapper.ModelVersionRecord model,
            MqttJsonProtocolDefinition compiledDefinition,
            MqttJsonExecutionPlan plan,
            List<ProtocolConfigurationMapper.SampleRecord> samples
    ) {
    }
}
