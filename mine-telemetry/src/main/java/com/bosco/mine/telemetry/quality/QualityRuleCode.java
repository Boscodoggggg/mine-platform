package com.bosco.mine.telemetry.quality;

/**
 * 可持久化、可聚合且保持低基数的数据质量规则编码。
 */
public enum QualityRuleCode {
    /** 设备时间超过未来容忍值。 */
    FUTURE_TIMESTAMP,
    /** 设备数据超过陈旧阈值。 */
    STALE_TIMESTAMP,
    /** 必填属性在本帧中缺失或为空。 */
    REQUIRED_MISSING,
    /** 数值低于策略下限。 */
    BELOW_MINIMUM,
    /** 数值高于策略上限。 */
    ABOVE_MAXIMUM
}
