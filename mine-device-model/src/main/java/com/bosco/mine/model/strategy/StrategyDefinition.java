package com.bosco.mine.model.strategy;

import java.util.Map;

/**
 * 一类受控策略的字段、类型和范围校验边界。
 */
public interface StrategyDefinition {

    /**
     * 获取稳定策略类型。
     *
     * @return 大写策略类型编码
     */
    String type();

    /**
     * 校验并规范化一个局部策略片段。
     *
     * @param values 外部输入字段
     * @return 可交给有效策略解析器的不可变字段
     * @throws StrategyValidationException 字段、类型或范围不合法
     */
    Map<String, Object> validate(Map<String, ?> values);
}
