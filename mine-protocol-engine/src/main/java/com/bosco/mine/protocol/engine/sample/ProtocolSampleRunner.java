package com.bosco.mine.protocol.engine.sample;

import com.bosco.mine.protocol.engine.json.MqttJsonExecutionPlan;
import com.bosco.mine.protocol.engine.json.ProtocolExecutionResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 使用与 Gateway 相同的执行计划回放协议黄金样本。
 */
public final class ProtocolSampleRunner {

    private static final int MAX_SAMPLES = 200;
    private static final int MAX_SAMPLE_BYTES = 64 * 1024;

    /**
     * 回放一组黄金样本并生成逐条门禁结果。
     *
     * @param plan 已编译计划
     * @param trustedIdentity 测试使用的可信身份
     * @param samples 黄金样本
     * @return 汇总报告
     */
    public TestReport run(
            MqttJsonExecutionPlan plan,
            MqttJsonExecutionPlan.TrustedIdentity trustedIdentity,
            List<ProtocolSample> samples
    ) {
        Objects.requireNonNull(plan, "执行计划不能为空");
        Objects.requireNonNull(trustedIdentity, "可信身份不能为空");
        if (samples == null || samples.isEmpty() || samples.size() > MAX_SAMPLES) {
            throw new IllegalArgumentException("黄金样本数量必须在 1 到 200 之间");
        }
        List<SampleResult> results = new ArrayList<>(samples.size());
        for (ProtocolSample sample : samples) {
            if (sample.payload().length > MAX_SAMPLE_BYTES) {
                results.add(new SampleResult(sample.name(), false, "样本超过 64 KiB 上限", null));
                continue;
            }
            ProtocolExecutionResult actual = plan.execute(sample.payload(), trustedIdentity);
            results.add(compare(sample, actual));
        }
        List<SampleResult> immutable = List.copyOf(results);
        return new TestReport(immutable.stream().allMatch(SampleResult::passed), immutable);
    }

    private SampleResult compare(ProtocolSample sample, ProtocolExecutionResult actual) {
        if (sample.expectedSuccess() != actual.success()) {
            return new SampleResult(sample.name(), false, "成功状态不符合预期", actual.errorCode());
        }
        if (sample.expectedSuccess() && !equivalent(sample.expectedProperties(), actual.properties())) {
            return new SampleResult(sample.name(), false, "解析属性不符合预期", null);
        }
        if (!sample.expectedSuccess() && !Objects.equals(sample.expectedErrorCode(), actual.errorCode())) {
            return new SampleResult(sample.name(), false, "错误码不符合预期", actual.errorCode());
        }
        return new SampleResult(sample.name(), true, "通过", actual.errorCode());
    }

    private boolean equivalent(Object expected, Object actual) {
        if (expected instanceof Number expectedNumber && actual instanceof Number actualNumber) {
            return decimal(expectedNumber).compareTo(decimal(actualNumber)) == 0;
        }
        if (expected instanceof Map<?, ?> expectedMap && actual instanceof Map<?, ?> actualMap) {
            return expectedMap.size() == actualMap.size()
                    && expectedMap.entrySet().stream().allMatch(entry ->
                    actualMap.containsKey(entry.getKey())
                            && equivalent(entry.getValue(), actualMap.get(entry.getKey())));
        }
        if (expected instanceof List<?> expectedList && actual instanceof List<?> actualList) {
            if (expectedList.size() != actualList.size()) return false;
            for (int index = 0; index < expectedList.size(); index++) {
                if (!equivalent(expectedList.get(index), actualList.get(index))) return false;
            }
            return true;
        }
        return Objects.equals(expected, actual);
    }

    private BigDecimal decimal(Number number) {
        return number instanceof BigDecimal value ? value : new BigDecimal(number.toString());
    }

    /**
     * 一次黄金样本测试的汇总结果。
     *
     * @param passed 是否全部通过
     * @param results 逐条结果
     */
    public record TestReport(boolean passed, List<SampleResult> results) {

        /** 冻结逐条结果。 */
        public TestReport {
            results = List.copyOf(results);
        }
    }

    /**
     * 单条样本结果。
     *
     * @param name 样本名称
     * @param passed 是否通过
     * @param message 结果说明
     * @param actualErrorCode 实际错误码
     */
    public record SampleResult(String name, boolean passed, String message, String actualErrorCode) {
    }
}
