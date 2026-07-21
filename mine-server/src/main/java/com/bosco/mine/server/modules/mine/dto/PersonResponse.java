package com.bosco.mine.server.modules.mine.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 不包含敏感明文的矿区人员响应。
 *
 * @param id 人员标识
 * @param organizationId 所属组织标识
 * @param siteId 所属矿区标识
 * @param personCode 人员编码
 * @param name 人员姓名
 * @param personType 人员岗位类型
 * @param phoneMasked 脱敏手机号
 * @param certificateMasked 脱敏证件号码
 * @param assignmentFrom 当前路由矿区任职开始日期
 * @param assignmentTo 当前路由矿区任职结束日期
 * @param status 人员状态
 * @param archivedAt 归档时间
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 * @param version 乐观锁版本
 */
public record PersonResponse(
        UUID id,
        UUID organizationId,
        UUID siteId,
        String personCode,
        String name,
        String personType,
        String phoneMasked,
        String certificateMasked,
        LocalDate assignmentFrom,
        LocalDate assignmentTo,
        String status,
        Instant archivedAt,
        Instant createdAt,
        Instant updatedAt,
        long version
) {
}
