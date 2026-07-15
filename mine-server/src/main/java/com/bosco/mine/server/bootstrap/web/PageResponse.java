package com.bosco.mine.server.bootstrap.web;

import java.util.List;
import java.util.Objects;

/**
 * 通用分页响应，成功时由控制器直接返回，不额外包裹结果对象。
 *
 * @param items 当前页数据
 * @param page  当前页码，从一开始
 * @param size  每页数量
 * @param total 符合条件的数据总数
 * @param <T>   数据项类型
 */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long total
) {

    /**
     * 创建不可变分页响应并校验分页参数。
     */
    public PageResponse {
        Objects.requireNonNull(items, "分页数据不能为空");
        if (page < 1) {
            throw new IllegalArgumentException("页码必须大于等于一");
        }
        if (size < 1) {
            throw new IllegalArgumentException("每页数量必须大于等于一");
        }
        if (total < 0) {
            throw new IllegalArgumentException("数据总数不能为负数");
        }
        items = List.copyOf(items);
    }
}
