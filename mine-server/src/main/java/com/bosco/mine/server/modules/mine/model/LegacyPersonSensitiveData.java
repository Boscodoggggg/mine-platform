package com.bosco.mine.server.modules.mine.model;

import java.util.UUID;

/**
 * 等待升级的人员敏感密文快照。
 *
 * @param id 人员唯一标识
 * @param phoneCiphertext 手机号密文
 * @param certificateCiphertext 证件号码密文
 */
public record LegacyPersonSensitiveData(
        UUID id,
        String phoneCiphertext,
        String certificateCiphertext
) {
}
