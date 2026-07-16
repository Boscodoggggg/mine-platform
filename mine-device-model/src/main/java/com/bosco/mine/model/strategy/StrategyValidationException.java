package com.bosco.mine.model.strategy;

import java.util.List;
import java.util.Objects;

/**
 * 表示策略片段没有通过白名单字段或类型范围校验。
 */
public final class StrategyValidationException extends IllegalArgumentException {

    private final List<Violation> violations;

    /**
     * 创建结构化策略校验异常。
     *
     * @param violations 不包含原始敏感值的字段问题
     */
    public StrategyValidationException(List<Violation> violations) {
        super("策略配置未通过校验");
        if (violations == null || violations.isEmpty()) {
            throw new IllegalArgumentException("策略校验问题不能为空");
        }
        this.violations = List.copyOf(violations);
    }

    /**
     * 获取全部字段问题。
     *
     * @return 不可变问题列表
     */
    public List<Violation> violations() {
        return violations;
    }

    /**
     * 单个策略字段的安全校验问题。
     *
     * @param field 字段名，整体问题使用美元符号
     * @param reason 中文安全原因
     */
    public record Violation(String field, String reason) {
        /** 校验并冻结字段问题。 */
        public Violation {
            field = Objects.requireNonNull(field, "校验字段不能为空");
            reason = Objects.requireNonNull(reason, "校验原因不能为空");
        }
    }
}
