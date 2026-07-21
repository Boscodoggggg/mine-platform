package com.bosco.mine.model.strategy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 按产品、设备组和单设备优先级确定性计算有效策略。
 *
 * <p>解析器只处理已经通过具体策略定义校验的受控 JSON 值。同优先级设备组对同一字段给出
 * 不同值时返回冲突，调用方不得消费部分合并结果。结构化值作为完整字段覆盖，不做深层合并。</p>
 */
public final class EffectiveStrategyResolver {

    private static final Comparator<ScopedStrategyFragment> APPLICATION_ORDER =
            Comparator.comparingInt(EffectiveStrategyResolver::scopeRank)
                    .thenComparing(fragment -> fragment.groupPriority() == null ? -1 : fragment.groupPriority())
                    .thenComparing(fragment -> fragment.scopeId().toString())
                    .thenComparing(fragment -> fragment.releaseId().toString());

    /**
     * 计算一个设备的有效策略及逐字段来源。
     *
     * @param fragments 当前产品、设备组和单设备的已发布片段
     * @return 有效值或稳定冲突列表
     */
    public Resolution resolve(Collection<ScopedStrategyFragment> fragments) {
        Objects.requireNonNull(fragments, "策略片段集合不能为空");
        List<ScopedStrategyFragment> ordered = fragments.stream()
                .map(fragment -> Objects.requireNonNull(fragment, "策略片段不能为空"))
                .sorted(APPLICATION_ORDER)
                .toList();
        requireSingleScope(ordered, StrategyScopeType.PRODUCT);
        requireSingleScope(ordered, StrategyScopeType.DEVICE);

        List<Conflict> conflicts = findGroupConflicts(ordered);
        if (!conflicts.isEmpty()) {
            return new Resolution(false, Map.of(), Map.of(), conflicts, null);
        }

        Map<String, Object> values = new TreeMap<>();
        Map<String, ValueSource> sources = new TreeMap<>();
        for (ScopedStrategyFragment fragment : ordered) {
            fragment.values().forEach((field, value) -> apply(values, sources, fragment, field, value));
        }
        Map<String, Object> immutableValues = immutableMapAllowingNull(values);
        return new Resolution(
                true,
                immutableValues,
                Map.copyOf(sources),
                List.of(),
                hash(immutableValues)
        );
    }

    private void apply(
            Map<String, Object> values,
            Map<String, ValueSource> sources,
            ScopedStrategyFragment fragment,
            String field,
            Object value
    ) {
        ValueSource currentSource = sources.get(field);
        if (fragment.scopeType() == StrategyScopeType.GROUP
                && currentSource != null
                && currentSource.scopeType() == StrategyScopeType.GROUP
                && Objects.equals(currentSource.groupPriority(), fragment.groupPriority())
                && StrategyValueNormalizer.canonicalKey(values.get(field)).equals(
                        StrategyValueNormalizer.canonicalKey(value))) {
            return;
        }
        values.put(field, StrategyValueNormalizer.normalize(value));
        sources.put(field, new ValueSource(
                fragment.scopeType(), fragment.scopeId(), fragment.releaseId(), fragment.groupPriority()
        ));
    }

    private List<Conflict> findGroupConflicts(List<ScopedStrategyFragment> ordered) {
        Map<Integer, Map<String, List<ScopedStrategyFragment>>> candidates = new TreeMap<>();
        ordered.stream()
                .filter(fragment -> fragment.scopeType() == StrategyScopeType.GROUP)
                .forEach(fragment -> fragment.values().keySet().forEach(field -> candidates
                        .computeIfAbsent(fragment.groupPriority(), ignored -> new TreeMap<>())
                        .computeIfAbsent(field, ignored -> new ArrayList<>())
                        .add(fragment)));
        List<Conflict> conflicts = new ArrayList<>();
        candidates.forEach((priority, fields) -> fields.forEach((field, contributors) -> {
            long distinctValues = contributors.stream()
                    .map(fragment -> StrategyValueNormalizer.canonicalKey(
                            fragment.values().get(field)))
                    .distinct()
                    .limit(2)
                    .count();
            if (distinctValues > 1) {
                List<UUID> groupIds = contributors.stream()
                        .map(ScopedStrategyFragment::scopeId)
                        .distinct()
                        .sorted(Comparator.comparing(UUID::toString))
                        .toList();
                conflicts.add(new Conflict(field, priority, groupIds));
            }
        }));
        return List.copyOf(conflicts);
    }

    private void requireSingleScope(List<ScopedStrategyFragment> fragments, StrategyScopeType type) {
        long count = fragments.stream().filter(fragment -> fragment.scopeType() == type).count();
        if (count > 1) {
            throw new IllegalArgumentException("同一设备只能存在一个当前" + type + "策略片段");
        }
    }

    private static int scopeRank(ScopedStrategyFragment fragment) {
        return switch (fragment.scopeType()) {
            case PRODUCT -> 0;
            case GROUP -> 1;
            case DEVICE -> 2;
        };
    }

    private static Map<String, Object> immutableMapAllowingNull(Map<String, Object> source) {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static String hash(Map<String, Object> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            values.forEach((field, value) -> digest.update(
                    (field.length() + ":" + field + "="
                            + StrategyValueNormalizer.canonicalKey(value) + "\n")
                            .getBytes(StandardCharsets.UTF_8)
            ));
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JRE 不支持 SHA-256", exception);
        }
    }

    /**
     * 一个字段当前最终值的来源。
     *
     * @param scopeType 作用范围类型
     * @param scopeId 作用范围标识
     * @param releaseId 发布标识
     * @param groupPriority 设备组优先级，非设备组为空
     */
    public record ValueSource(
            StrategyScopeType scopeType,
            UUID scopeId,
            UUID releaseId,
            Integer groupPriority
    ) {
    }

    /**
     * 同优先级设备组的字段冲突。
     *
     * @param field 冲突字段
     * @param groupPriority 设备组优先级
     * @param groupIds 涉及的稳定排序设备组标识
     */
    public record Conflict(String field, int groupPriority, List<UUID> groupIds) {
        /** 冻结冲突中的设备组标识。 */
        public Conflict {
            field = Objects.requireNonNull(field, "冲突字段不能为空");
            groupIds = List.copyOf(groupIds);
        }
    }

    /**
     * 有效策略解析结果。
     *
     * @param valid 是否不存在冲突
     * @param values 最终值，冲突时为空
     * @param sources 逐字段来源，冲突时为空
     * @param conflicts 稳定冲突列表
     * @param contentHash 最终值哈希，冲突时为空
     */
    public record Resolution(
            boolean valid,
            Map<String, Object> values,
            Map<String, ValueSource> sources,
            List<Conflict> conflicts,
            String contentHash
    ) {
        /** 冻结解析结果。 */
        public Resolution {
            values = immutableMapAllowingNull(values);
            sources = Map.copyOf(sources);
            conflicts = List.copyOf(conflicts);
        }
    }
}
