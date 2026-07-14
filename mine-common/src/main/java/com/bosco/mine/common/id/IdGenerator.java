package com.bosco.mine.common.id;

import com.github.f4b6a3.uuid.UuidCreator;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 生成可按时间排序的平台 UUIDv7 标识。
 */
public final class IdGenerator {

    private final Supplier<UUID> supplier;

    private IdGenerator(Supplier<UUID> supplier) {
        this.supplier = supplier;
    }

    /**
     * 创建基于经过验证的 UUIDv7 实现的标识生成器。
     *
     * @return 时间有序标识生成器
     */
    public static IdGenerator timeOrdered() {
        return new IdGenerator(UuidCreator::getTimeOrderedEpoch);
    }

    /**
     * 生成下一个 UUIDv7 标识。
     *
     * @return 新标识
     */
    public UUID next() {
        return supplier.get();
    }
}
