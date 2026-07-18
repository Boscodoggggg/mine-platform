package com.bosco.mine.telemetry.location;

import com.bosco.mine.model.location.LocationObservation;
import com.bosco.mine.telemetry.location.snapshot.LocationRuntimeRegistry;
import com.bosco.mine.telemetry.location.snapshot.PermanentLocationSnapshotException;
import com.bosco.mine.telemetry.location.transform.CoordinateTransformRegistry;
import com.bosco.mine.telemetry.location.transform.CoordinateTransformer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 把最后正确通用快照编译为按矿区有界缓存的定位热路径模型。 */
final class LocationRuntimeResolver {

    private final LocationRuntimeRegistry registry;
    private final CoordinateTransformRegistry transformRegistry;
    private final LocationQualityEvaluator qualityEvaluator;
    private final LocationPipelineMetrics metrics;
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();
    private final ConcurrentHashMap<UUID, CachedRuntime> runtimes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, UUID> deviceSites = new ConcurrentHashMap<>();

    /** 创建只读取已激活快照的发生时解析器。 */
    LocationRuntimeResolver(
            LocationRuntimeRegistry registry,
            CoordinateTransformRegistry transformRegistry,
            LocationQualityEvaluator qualityEvaluator,
            LocationPipelineMetrics metrics
    ) {
        this.registry = Objects.requireNonNull(registry, "定位运行注册表不能为空");
        this.transformRegistry = Objects.requireNonNull(
                transformRegistry, "坐标转换注册表不能为空");
        this.qualityEvaluator = Objects.requireNonNull(
                qualityEvaluator, "定位质量判定器不能为空");
        this.metrics = Objects.requireNonNull(metrics, "定位流水线指标不能为空");
    }

    /** 按观测发生时间解析可信设备、标签、转换版本和资产绑定。 */
    ResolvedFact resolve(LocationObservation observation) {
        LocationRuntimeRegistry.RuntimeSnapshot snapshot = findSnapshot(observation);
        RuntimeModel model;
        try {
            model = runtimes.compute(snapshot.siteId(), (siteId, existing) ->
                    existing != null && existing.snapshotId().equals(snapshot.snapshotId())
                            ? existing
                            : new CachedRuntime(snapshot.snapshotId(), compile(snapshot)))
                    .model();
        } catch (QuarantineDecision exception) {
            throw exception.organizationId() == null
                    ? new QuarantineDecision(
                    exception.reason(), snapshot.organizationId(), snapshot.siteId(), exception)
                    : exception;
        } catch (RuntimeException exception) {
            throw new QuarantineDecision(
                    LocationFactWriter.QuarantineReason.SNAPSHOT_INVALID,
                    snapshot.organizationId(), snapshot.siteId(), exception);
        }
        return model.resolve(observation, qualityEvaluator, metrics);
    }

    private LocationRuntimeRegistry.RuntimeSnapshot findSnapshot(
            LocationObservation observation
    ) {
        UUID cachedSiteId = deviceSites.get(observation.sourceDeviceId());
        if (cachedSiteId != null) {
            LocationRuntimeRegistry.RuntimeSnapshot cached = registry.current(cachedSiteId)
                    .filter(snapshot -> snapshot.items().containsKey(
                            "SOURCE_DEVICE:" + observation.sourceDeviceId()))
                    .orElse(null);
            if (cached != null) {
                return cached;
            }
            deviceSites.remove(observation.sourceDeviceId(), cachedSiteId);
        }
        try {
            LocationRuntimeRegistry.RuntimeSnapshot found = registry
                    .currentForSourceDevice(observation.sourceDeviceId())
                    .orElseThrow(() -> new QuarantineDecision(
                            registry.hasActiveSnapshots()
                                    ? LocationFactWriter.QuarantineReason
                                    .SOURCE_DEVICE_UNAUTHORIZED
                                    : LocationFactWriter.QuarantineReason.SNAPSHOT_UNAVAILABLE,
                            null, null));
            deviceSites.put(observation.sourceDeviceId(), found.siteId());
            return found;
        } catch (PermanentLocationSnapshotException exception) {
            throw new QuarantineDecision(
                    LocationFactWriter.QuarantineReason.SNAPSHOT_INVALID,
                    null, null, exception);
        }
    }

    private RuntimeModel compile(LocationRuntimeRegistry.RuntimeSnapshot snapshot) {
        Map<UUID, String> sourceDevices = new LinkedHashMap<>();
        Map<String, Tag> tags = new LinkedHashMap<>();
        Map<String, CoordinateSystem> systems = new LinkedHashMap<>();
        List<DeviceBinding> deviceBindings = new ArrayList<>();
        List<TagBinding> tagBindings = new ArrayList<>();
        List<Transform> transforms = new ArrayList<>();
        CoordinateSystem unified = null;

        for (LocationRuntimeRegistry.RuntimeItem item : snapshot.items().values()) {
            JsonNode content = readObject(item.contentJson());
            switch (item.itemType()) {
                case "SOURCE_DEVICE" -> {
                    UUID id = requiredUuid(content, "deviceId");
                    requireKey(item, id);
                    if (sourceDevices.put(id, requiredText(content, "status")) != null) {
                        invalid("来源设备标识重复");
                    }
                }
                case "LOCATION_TAG" -> {
                    UUID id = requiredUuid(content, "tagId");
                    requireKey(item, id);
                    Tag previous = tags.put(requiredText(content, "tagCode"),
                            new Tag(id, requiredText(content, "status")));
                    if (previous != null) {
                        invalid("标签编码重复");
                    }
                }
                case "COORDINATE_SYSTEM" -> {
                    UUID id = requiredUuid(content, "coordinateSystemId");
                    requireKey(item, id);
                    CoordinateSystem system = new CoordinateSystem(
                            id, requiredText(content, "systemCode"),
                            requiredText(content, "status"),
                            requiredBoolean(content, "unified"),
                            bounds(content));
                    if (systems.put(system.code(), system) != null) {
                        invalid("坐标系编码重复");
                    }
                    if (system.unified() && "ACTIVE".equals(system.status())) {
                        if (unified != null) {
                            invalid("有效统一坐标系重复");
                        }
                        unified = system;
                    }
                }
                case "VEHICLE_DEVICE_BINDING" -> deviceBindings.add(new DeviceBinding(
                        requiredUuid(content, "bindingId"),
                        requiredUuid(content, "deviceId"),
                        requiredUuid(content, "vehicleId"),
                        requiredText(content, "role"),
                        requiredInstant(content, "validFrom"),
                        optionalInstant(content, "validTo")));
                case "VEHICLE_TAG_BINDING" -> tagBindings.add(new TagBinding(
                        "VEHICLE_TAG", requiredUuid(content, "bindingId"),
                        requiredUuid(content, "tagId"),
                        requiredUuid(content, "vehicleId"), null,
                        requiredInstant(content, "validFrom"),
                        optionalInstant(content, "validTo")));
                case "PERSON_TAG_BINDING" -> tagBindings.add(new TagBinding(
                        "PERSON_TAG", requiredUuid(content, "bindingId"),
                        requiredUuid(content, "tagId"), null,
                        requiredUuid(content, "personId"),
                        requiredInstant(content, "validFrom"),
                        optionalInstant(content, "validTo")));
                default -> {
                    // 其他主数据条目不参与定位热路径索引。
                }
            }
        }
        if (unified == null) {
            invalid("缺少有效统一坐标系");
        }
        CoordinateSystem target = unified;
        for (LocationRuntimeRegistry.RuntimeItem item : snapshot.items().values()) {
            if (!"COORDINATE_TRANSFORM".equals(item.itemType())) {
                continue;
            }
            JsonNode content = readObject(item.contentJson());
            UUID transformId = requiredUuid(content, "coordinateTransformId");
            requireKey(item, transformId);
            UUID sourceId = requiredUuid(content, "sourceCoordinateSystemId");
            UUID targetId = requiredUuid(content, "targetCoordinateSystemId");
            CoordinateSystem source = systems.values().stream()
                    .filter(system -> system.id().equals(sourceId)).findFirst()
                    .orElseThrow(() -> invalidDecision("转换缺少来源坐标系"));
            CoordinateSystem declaredTarget = systems.values().stream()
                    .filter(system -> system.id().equals(targetId)).findFirst()
                    .orElseThrow(() -> invalidDecision("转换缺少目标坐标系"));
            if (!target.id().equals(declaredTarget.id())) {
                continue;
            }
            CoordinateTransformer.Algorithm algorithm = CoordinateTransformer.Algorithm.valueOf(
                    requiredText(content, "algorithm"));
            CoordinateTransformer.TransformParameters parameters = parameters(
                    algorithm, requiredObject(content, "parameters"));
            transforms.add(new Transform(
                    transformId, source.id(), declaredTarget.id(),
                    requiredInstant(content, "validFrom"),
                    optionalInstant(content, "validTo"),
                    algorithm,
                    transformRegistry.resolve(new CoordinateTransformer.TransformSnapshot(
                            transformId, algorithm, parameters, declaredTarget.bounds()))));
        }
        return new RuntimeModel(
                snapshot.organizationId(), snapshot.siteId(), Map.copyOf(sourceDevices),
                Map.copyOf(tags), Map.copyOf(systems), target,
                List.copyOf(deviceBindings), List.copyOf(tagBindings),
                List.copyOf(transforms));
    }

    private CoordinateTransformer.TransformParameters parameters(
            CoordinateTransformer.Algorithm algorithm,
            JsonNode parameters
    ) {
        return switch (algorithm) {
            case IDENTITY -> {
                if (!parameters.isEmpty()) {
                    invalid("恒等转换参数必须为空");
                }
                yield new CoordinateTransformer.IdentityParameters();
            }
            case AFFINE_3D -> new CoordinateTransformer.Affine3dParameters(
                    requiredDoubleArray(parameters, "matrix", 16));
            case WGS84_ENU -> new CoordinateTransformer.Wgs84EnuParameters(
                    requiredDouble(parameters, "originLongitude"),
                    requiredDouble(parameters, "originLatitude"),
                    requiredDouble(parameters, "originAltitude"));
        };
    }

    private CoordinateTransformer.TargetBounds bounds(JsonNode content) {
        return new CoordinateTransformer.TargetBounds(
                requiredDouble(content, "minX"), requiredDouble(content, "maxX"),
                requiredDouble(content, "minY"), requiredDouble(content, "maxY"),
                optionalDouble(content, "minZ"), optionalDouble(content, "maxZ"));
    }

    private void requireKey(LocationRuntimeRegistry.RuntimeItem item, UUID id) {
        if (!item.itemKey().endsWith(id.toString())) {
            invalid("快照条目键与内容标识不一致");
        }
    }

    private JsonNode readObject(String value) {
        try {
            JsonNode result = jsonMapper.readTree(value);
            if (result == null || !result.isObject()) {
                invalid("快照条目内容不是对象");
            }
            return result;
        } catch (JacksonException exception) {
            throw invalidDecision("快照条目 JSON 无法解析", exception);
        }
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            invalid("快照缺少必要文本字段");
        }
        return value.asString();
    }

    private UUID requiredUuid(JsonNode node, String field) {
        try {
            return UUID.fromString(requiredText(node, field));
        } catch (IllegalArgumentException exception) {
            throw invalidDecision("快照 UUID 字段不合法", exception);
        }
    }

    private Instant requiredInstant(JsonNode node, String field) {
        try {
            return Instant.parse(requiredText(node, field));
        } catch (IllegalArgumentException exception) {
            throw invalidDecision("快照时间字段不合法", exception);
        }
    }

    private Instant optionalInstant(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return requiredInstant(node, field);
    }

    private double requiredDouble(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber() || !Double.isFinite(value.doubleValue())) {
            invalid("快照数值字段不合法");
        }
        return value.doubleValue();
    }

    private Double optionalDouble(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return requiredDouble(node, field);
    }

    private boolean requiredBoolean(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            invalid("快照布尔字段不合法");
        }
        return value.asBoolean();
    }

    private JsonNode requiredObject(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isObject()) {
            invalid("快照对象字段不合法");
        }
        return value;
    }

    private List<Double> requiredDoubleArray(JsonNode node, String field, int length) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray() || value.size() != length) {
            invalid("快照矩阵字段长度不合法");
        }
        List<Double> result = new ArrayList<>(length);
        for (JsonNode item : value) {
            if (!item.isNumber() || !Double.isFinite(item.doubleValue())) {
                invalid("快照矩阵包含非有限数值");
            }
            result.add(item.doubleValue());
        }
        return List.copyOf(result);
    }

    private void invalid(String message) {
        throw invalidDecision(message);
    }

    private QuarantineDecision invalidDecision(String message) {
        return new QuarantineDecision(
                LocationFactWriter.QuarantineReason.SNAPSHOT_INVALID,
                null, null, new IllegalArgumentException(message));
    }

    private QuarantineDecision invalidDecision(String message, Throwable cause) {
        return new QuarantineDecision(
                LocationFactWriter.QuarantineReason.SNAPSHOT_INVALID,
                null, null, new IllegalArgumentException(message, cause));
    }

    /** 已解析且可原子写入的位置事实。 */
    record ResolvedFact(
            UUID organizationId,
            UUID siteId,
            UUID tagId,
            String tagCode,
            UUID sourceCoordinateSystemId,
            UUID targetCoordinateSystemId,
            CoordinateTransformer.TransformedCoordinate coordinate,
            String bindingType,
            UUID bindingId,
            UUID vehicleId,
            UUID personId,
            LocationQualityEvaluator.Evaluation quality
    ) {
    }

    /** 需要持久隔离且不可通过重投修复的运行语义判定。 */
    static final class QuarantineDecision extends RuntimeException {
        private final LocationFactWriter.QuarantineReason reason;
        private final UUID organizationId;
        private final UUID siteId;

        QuarantineDecision(
                LocationFactWriter.QuarantineReason reason,
                UUID organizationId,
                UUID siteId
        ) {
            this(reason, organizationId, siteId, null);
        }

        QuarantineDecision(
                LocationFactWriter.QuarantineReason reason,
                UUID organizationId,
                UUID siteId,
                Throwable cause
        ) {
            super(reason.name(), cause);
            this.reason = Objects.requireNonNull(reason);
            this.organizationId = organizationId;
            this.siteId = siteId;
        }

        LocationFactWriter.QuarantineReason reason() {
            return reason;
        }

        UUID organizationId() {
            return organizationId;
        }

        UUID siteId() {
            return siteId;
        }
    }

    private record CachedRuntime(UUID snapshotId, RuntimeModel model) {
    }

    private record RuntimeModel(
            UUID organizationId,
            UUID siteId,
            Map<UUID, String> sourceDevices,
            Map<String, Tag> tags,
            Map<String, CoordinateSystem> systems,
            CoordinateSystem unified,
            List<DeviceBinding> deviceBindings,
            List<TagBinding> tagBindings,
            List<Transform> transforms
    ) {

        private ResolvedFact resolve(
                LocationObservation observation,
                LocationQualityEvaluator evaluator,
                LocationPipelineMetrics metrics
        ) {
            if (!"ACTIVE".equals(sourceDevices.get(observation.sourceDeviceId()))
                    || deviceBindings.stream().noneMatch(binding ->
                    binding.deviceId().equals(observation.sourceDeviceId())
                            && binding.contains(observation.occurredAt()))) {
                throw quarantine(LocationFactWriter.QuarantineReason.SOURCE_DEVICE_UNAUTHORIZED);
            }
            Tag tag = tags.get(observation.tagCode());
            if (tag == null) {
                throw quarantine(LocationFactWriter.QuarantineReason.UNKNOWN_TAG);
            }
            if (!"ACTIVE".equals(tag.status())) {
                throw quarantine(LocationFactWriter.QuarantineReason.ARCHIVED_TAG);
            }
            CoordinateSystem source = systems.get(observation.coordinateSystemCode());
            if (source == null || !"ACTIVE".equals(source.status())) {
                throw quarantine(LocationFactWriter.QuarantineReason.TRANSFORM_MISSING);
            }
            if (!source.contains(observation)) {
                throw quarantine(LocationFactWriter.QuarantineReason.OUT_OF_BOUNDS);
            }
            List<Transform> selected = transforms.stream()
                    .filter(transform -> transform.sourceId().equals(source.id())
                            && transform.targetId().equals(unified.id())
                            && transform.contains(observation.occurredAt()))
                    .toList();
            if (selected.isEmpty()) {
                throw quarantine(LocationFactWriter.QuarantineReason.TRANSFORM_MISSING);
            }
            if (selected.size() > 1) {
                throw quarantine(LocationFactWriter.QuarantineReason.TRANSFORM_VERSION_CONFLICT);
            }
            Transform selectedTransform = selected.getFirst();
            CoordinateTransformer.TransformedCoordinate coordinate;
            try {
                coordinate = selectedTransform.transformer().transform(
                        new CoordinateTransformer.SourceCoordinate(
                                observation.x(), observation.y(), observation.z()));
                metrics.transform(
                        LocationPipelineMetrics.TransformAlgorithm.valueOf(
                                selectedTransform.algorithm().name()),
                        LocationPipelineMetrics.TransformResult.SUCCESS);
            } catch (CoordinateTransformer.OutOfBoundsException exception) {
                metrics.transform(
                        LocationPipelineMetrics.TransformAlgorithm.valueOf(
                                selectedTransform.algorithm().name()),
                        LocationPipelineMetrics.TransformResult.FAILURE);
                throw quarantine(LocationFactWriter.QuarantineReason.OUT_OF_BOUNDS);
            } catch (IllegalArgumentException exception) {
                metrics.transform(
                        LocationPipelineMetrics.TransformAlgorithm.valueOf(
                                selectedTransform.algorithm().name()),
                        LocationPipelineMetrics.TransformResult.FAILURE);
                throw quarantine(LocationFactWriter.QuarantineReason.NON_FINITE_COORDINATE);
            }
            List<TagBinding> selectedBindings = tagBindings.stream()
                    .filter(binding -> binding.tagId().equals(tag.id())
                            && binding.contains(observation.occurredAt()))
                    .toList();
            if (selectedBindings.size() > 1) {
                throw quarantine(LocationFactWriter.QuarantineReason.SNAPSHOT_INVALID);
            }
            TagBinding binding = selectedBindings.isEmpty() ? null : selectedBindings.getFirst();
            return new ResolvedFact(
                    organizationId, siteId, tag.id(), observation.tagCode(),
                    source.id(), unified.id(), coordinate,
                    binding == null ? null : binding.type(),
                    binding == null ? null : binding.id(),
                    binding == null ? null : binding.vehicleId(),
                    binding == null ? null : binding.personId(), evaluator.evaluate(observation));
        }

        private QuarantineDecision quarantine(LocationFactWriter.QuarantineReason reason) {
            return new QuarantineDecision(reason, organizationId, siteId);
        }
    }

    private record Tag(UUID id, String status) {
    }

    private record CoordinateSystem(
            UUID id,
            String code,
            String status,
            boolean unified,
            CoordinateTransformer.TargetBounds bounds
    ) {
        private boolean contains(LocationObservation observation) {
            boolean outside = observation.x() < bounds.minX()
                    || observation.x() > bounds.maxX()
                    || observation.y() < bounds.minY()
                    || observation.y() > bounds.maxY();
            if (observation.z() != null && bounds.minZ() != null) {
                outside = outside || observation.z() < bounds.minZ()
                        || observation.z() > bounds.maxZ();
            }
            return !outside;
        }
    }

    private record DeviceBinding(
            UUID id,
            UUID deviceId,
            UUID vehicleId,
            String role,
            Instant validFrom,
            Instant validTo
    ) {
        private boolean contains(Instant time) {
            return !time.isBefore(validFrom) && (validTo == null || time.isBefore(validTo));
        }
    }

    private record TagBinding(
            String type,
            UUID id,
            UUID tagId,
            UUID vehicleId,
            UUID personId,
            Instant validFrom,
            Instant validTo
    ) {
        private boolean contains(Instant time) {
            return !time.isBefore(validFrom) && (validTo == null || time.isBefore(validTo));
        }
    }

    private record Transform(
            UUID id,
            UUID sourceId,
            UUID targetId,
            Instant validFrom,
            Instant validTo,
            CoordinateTransformer.Algorithm algorithm,
            CoordinateTransformer transformer
    ) {
        private boolean contains(Instant time) {
            return !time.isBefore(validFrom) && (validTo == null || time.isBefore(validTo));
        }
    }
}
