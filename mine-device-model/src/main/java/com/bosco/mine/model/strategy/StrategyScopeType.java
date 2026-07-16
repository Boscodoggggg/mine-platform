package com.bosco.mine.model.strategy;

/**
 * 策略配置可以生效的作用范围。
 */
public enum StrategyScopeType {
    /** 产品默认配置。 */
    PRODUCT,
    /** 设备组覆盖配置。 */
    GROUP,
    /** 单设备最终覆盖配置。 */
    DEVICE
}
