package com.bosco.mine.server.modules.mine.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 矿区人员持久化快照，敏感明文不会进入该模型。
 *
 * @param id 人员标识
 * @param organizationId 所属组织标识
 * @param siteId 所属矿区标识
 * @param personCode 人员编码
 * @param name 人员姓名
 * @param personType 人员岗位类型
 * @param phoneCiphertext 手机号密文
 * @param phoneMasked 脱敏手机号
 * @param certificateCiphertext 证件号码密文
 * @param certificateMasked 脱敏证件号码
 * @param employmentFrom 任职开始日期
 * @param employmentTo 任职结束日期
 * @param status 人员状态
 * @param archivedAt 归档时间
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 * @param version 乐观锁版本
 */
public record MinePerson(
        UUID id,
        UUID organizationId,
        UUID siteId,
        String personCode,
        String name,
        String personType,
        String phoneCiphertext,
        String phoneMasked,
        String certificateCiphertext,
        String certificateMasked,
        LocalDate employmentFrom,
        LocalDate employmentTo,
        String status,
        Instant archivedAt,
        Instant createdAt,
        Instant updatedAt,
        long version
) {
}
