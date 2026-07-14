package com.bosco.mine.common.id;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验平台标识生成器输出 UUIDv7 且保持时间顺序。
 */
class IdGeneratorTest {

    @Test
    void 应生成唯一且递增的UUIDv7() {
        IdGenerator generator = IdGenerator.timeOrdered();
        List<UUID> identifiers = new ArrayList<>();

        for (int index = 0; index < 256; index++) {
            identifiers.add(generator.next());
        }

        assertThat(identifiers).allMatch(identifier -> identifier.version() == 7);
        assertThat(new HashSet<>(identifiers)).hasSameSizeAs(identifiers);
        assertThat(identifiers).isSorted();
    }
}
