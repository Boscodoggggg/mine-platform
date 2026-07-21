package com.bosco.mine.model.spatial;

/**
 * 位置点相对已发布空间区域的固定分类结果。
 */
public enum SpatialClassification {
    /** 位于区域内部且满足高度条件。 */
    INSIDE,
    /** 位于区域外部或不满足高度条件。 */
    OUTSIDE,
    /** 位于边界容差带内，应保持上一稳定状态。 */
    BOUNDARY,
    /** 二维命中带高度区域，但位置点没有 Z 坐标。 */
    HEIGHT_UNKNOWN
}
