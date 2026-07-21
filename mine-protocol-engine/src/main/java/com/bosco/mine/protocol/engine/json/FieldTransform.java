package com.bosco.mine.protocol.engine.json;

/**
 * 平台允许协议字段使用的确定性转换类型。
 */
public enum FieldTransform {
    /** 不转换，仅进行受控类型读取。 */
    NONE,
    /** 数值乘以缩放系数。 */
    SCALE,
    /** 数值加上偏移量。 */
    OFFSET,
    /** 数值先缩放再增加偏移量。 */
    SCALE_OFFSET,
    /** 使用发布配置中的有限码表转换。 */
    ENUM
}
