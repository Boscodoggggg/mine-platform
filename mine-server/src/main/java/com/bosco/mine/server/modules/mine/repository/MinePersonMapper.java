package com.bosco.mine.server.modules.mine.repository;

import com.bosco.mine.server.modules.mine.model.MineDriverQualification;
import com.bosco.mine.server.modules.mine.model.MinePerson;
import com.bosco.mine.server.modules.mine.model.LegacyPersonSensitiveData;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 按组织人员主档和矿区时态任职作用域访问人员与司机资质。
 */
@Mapper
public interface MinePersonMapper {

    /** 插入已加密的组织级人员主档。 */
    @Insert("""
            INSERT INTO mine_person (
                id, organization_id, person_code, name, person_type,
                phone_ciphertext, phone_masked, certificate_ciphertext, certificate_masked,
                created_by, updated_by
            ) VALUES (
                #{id}, #{organizationId}, #{personCode}, #{name}, #{personType},
                #{phoneCiphertext}, #{phoneMasked}, #{certificateCiphertext}, #{certificateMasked},
                #{operatorId}, #{operatorId}
            )
            """)
    int insertPerson(
            @Param("id") UUID id,
            @Param("organizationId") UUID organizationId,
            @Param("personCode") String personCode,
            @Param("name") String name,
            @Param("personType") String personType,
            @Param("phoneCiphertext") String phoneCiphertext,
            @Param("phoneMasked") String phoneMasked,
            @Param("certificateCiphertext") String certificateCiphertext,
            @Param("certificateMasked") String certificateMasked,
            @Param("operatorId") UUID operatorId
    );

    /** 插入人员在矿区的左闭右开任职区间。 */
    @Insert("""
            INSERT INTO mine_person_site_assignment (
                id, organization_id, site_id, person_id, valid_from, valid_to,
                reason, created_by, updated_by
            ) VALUES (
                #{id}, #{organizationId}, #{siteId}, #{personId}, #{validFrom}, #{validTo},
                #{reason}, #{operatorId}, #{operatorId}
            )
            """)
    int insertSiteAssignment(
            @Param("id") UUID id,
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("personId") UUID personId,
            @Param("validFrom") Instant validFrom,
            @Param("validTo") Instant validTo,
            @Param("reason") String reason,
            @Param("operatorId") UUID operatorId
    );

    /** 查询曾在指定矿区任职的人员主档。 */
    @Select("""
            SELECT person.id, person.organization_id AS organizationId,
                   CAST(#{siteId} AS UUID) AS siteId,
                   person.person_code AS personCode, person.name,
                   person.person_type AS personType,
                   NULL::TEXT AS phoneCiphertext,
                   person.phone_masked AS phoneMasked,
                   NULL::TEXT AS certificateCiphertext,
                   person.certificate_masked AS certificateMasked,
                   (assignment.valid_from AT TIME ZONE #{timezone})::DATE AS assignmentFrom,
                   CASE WHEN assignment.valid_to IS NULL THEN NULL
                        ELSE (assignment.valid_to AT TIME ZONE #{timezone})::DATE - 1
                   END AS assignmentTo,
                   person.status, person.archived_at AS archivedAt,
                   person.created_at AS createdAt, person.updated_at AS updatedAt,
                   person.version
            FROM mine_person person
            JOIN LATERAL (
                SELECT candidate.valid_from, candidate.valid_to, candidate.valid_period
                FROM mine_person_site_assignment candidate
                WHERE candidate.organization_id = person.organization_id
                  AND candidate.person_id = person.id
                  AND candidate.site_id = #{siteId}
                ORDER BY (candidate.valid_period @> CURRENT_TIMESTAMP) DESC,
                         candidate.valid_from DESC, candidate.id
                LIMIT 1
            ) assignment ON TRUE
            WHERE person.organization_id = #{organizationId}
              AND person.id = #{personId}
            """)
    Optional<MinePerson> findPerson(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("personId") UUID personId,
            @Param("timezone") String timezone
    );

    /** 查询当前正在指定矿区任职的人员主档。 */
    @Select("""
            SELECT person.id, person.organization_id AS organizationId,
                   CAST(#{siteId} AS UUID) AS siteId,
                   person.person_code AS personCode, person.name,
                   person.person_type AS personType,
                   person.phone_ciphertext AS phoneCiphertext,
                   person.phone_masked AS phoneMasked,
                   person.certificate_ciphertext AS certificateCiphertext,
                   person.certificate_masked AS certificateMasked,
                   (assignment.valid_from AT TIME ZONE #{timezone})::DATE AS assignmentFrom,
                   CASE WHEN assignment.valid_to IS NULL THEN NULL
                        ELSE (assignment.valid_to AT TIME ZONE #{timezone})::DATE - 1
                   END AS assignmentTo,
                   person.status, person.archived_at AS archivedAt,
                   person.created_at AS createdAt, person.updated_at AS updatedAt,
                   person.version
            FROM mine_person person
            JOIN mine_person_site_assignment assignment
              ON assignment.organization_id = person.organization_id
             AND assignment.person_id = person.id
             AND assignment.site_id = #{siteId}
             AND assignment.valid_period @> CURRENT_TIMESTAMP
            WHERE person.organization_id = #{organizationId}
              AND person.id = #{personId}
            """)
    Optional<MinePerson> findCurrentPerson(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("personId") UUID personId,
            @Param("timezone") String timezone
    );

    /** 锁定人员时间线，串行化任职调整和依赖当前任职的业务写入。 */
    @Select("""
            SELECT pg_advisory_xact_lock(
                hashtextextended(CAST(#{personId} AS TEXT), 487311842019)
            )
            """)
    String lockPersonTimeline(@Param("personId") UUID personId);

    /** 分页查询曾在指定矿区任职的脱敏人员投影。 */
    @Select("""
            <script>
            SELECT person.id, person.organization_id AS organizationId,
                   CAST(#{siteId} AS UUID) AS siteId,
                   person.person_code AS personCode, person.name,
                   person.person_type AS personType,
                   NULL::TEXT AS phoneCiphertext, person.phone_masked AS phoneMasked,
                   NULL::TEXT AS certificateCiphertext,
                   person.certificate_masked AS certificateMasked,
                   (assignment.valid_from AT TIME ZONE #{timezone})::DATE AS assignmentFrom,
                   CASE WHEN assignment.valid_to IS NULL THEN NULL
                        ELSE (assignment.valid_to AT TIME ZONE #{timezone})::DATE - 1
                   END AS assignmentTo,
                   person.status, person.archived_at AS archivedAt,
                   person.created_at AS createdAt, person.updated_at AS updatedAt,
                   person.version
            FROM mine_person person
            JOIN LATERAL (
                SELECT candidate.valid_from, candidate.valid_to, candidate.valid_period
                FROM mine_person_site_assignment candidate
                WHERE candidate.organization_id = person.organization_id
                  AND candidate.person_id = person.id
                  AND candidate.site_id = #{siteId}
                ORDER BY (candidate.valid_period @> CURRENT_TIMESTAMP) DESC,
                         candidate.valid_from DESC, candidate.id
                LIMIT 1
            ) assignment ON TRUE
            WHERE person.organization_id = #{organizationId}
              <if test="keyword != null">
                AND (person.person_code ILIKE '%' || #{keyword} || '%'
                     OR person.name ILIKE '%' || #{keyword} || '%')
              </if>
              <if test="status != null">
                AND person.status = #{status}
              </if>
            ORDER BY person.name, person.id
            OFFSET #{offset} LIMIT #{limit}
            </script>
            """)
    List<MinePerson> findPeople(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("offset") long offset,
            @Param("limit") int limit,
            @Param("timezone") String timezone
    );

    /** 统计曾在指定矿区任职的人员。 */
    @Select("""
            <script>
            SELECT COUNT(*)
            FROM mine_person person
            WHERE person.organization_id = #{organizationId}
              AND EXISTS (
                  SELECT 1
                  FROM mine_person_site_assignment assignment
                  WHERE assignment.organization_id = person.organization_id
                    AND assignment.person_id = person.id
                    AND assignment.site_id = #{siteId}
              )
              <if test="keyword != null">
                AND (person.person_code ILIKE '%' || #{keyword} || '%'
                     OR person.name ILIKE '%' || #{keyword} || '%')
              </if>
              <if test="status != null">
                AND person.status = #{status}
              </if>
            </script>
            """)
    long countPeople(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("keyword") String keyword,
            @Param("status") String status
    );

    /** 使用乐观锁修改人员普通字段并保留敏感密文。 */
    @Update("""
            UPDATE mine_person person
            SET name = #{name},
                person_type = #{personType},
                status = #{status},
                updated_at = CURRENT_TIMESTAMP,
                updated_by = #{operatorId},
                version = version + 1
            WHERE person.organization_id = #{organizationId}
              AND person.id = #{personId}
              AND person.version = #{version}
              AND person.status <> 'ARCHIVED'
              AND EXISTS (
                  SELECT 1 FROM mine_person_site_assignment assignment
                    WHERE assignment.organization_id = person.organization_id
                    AND assignment.person_id = person.id
                    AND assignment.site_id = #{siteId}
                    AND assignment.valid_period @> CURRENT_TIMESTAMP
              )
            """)
    int updatePerson(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("personId") UUID personId,
            @Param("name") String name,
            @Param("personType") String personType,
            @Param("status") String status,
            @Param("version") long version,
            @Param("operatorId") UUID operatorId
    );

    /** 使用乐观锁独立更新人员敏感密文及脱敏投影。 */
    @Update("""
            UPDATE mine_person person
            SET phone_ciphertext = #{phoneCiphertext},
                phone_masked = #{phoneMasked},
                certificate_ciphertext = #{certificateCiphertext},
                certificate_masked = #{certificateMasked},
                updated_at = CURRENT_TIMESTAMP,
                updated_by = #{operatorId},
                version = version + 1
            WHERE person.organization_id = #{organizationId}
              AND person.id = #{personId}
              AND person.version = #{version}
              AND person.status <> 'ARCHIVED'
              AND EXISTS (
                  SELECT 1 FROM mine_person_site_assignment assignment
                    WHERE assignment.organization_id = person.organization_id
                    AND assignment.person_id = person.id
                    AND assignment.site_id = #{siteId}
                    AND assignment.valid_period @> CURRENT_TIMESTAMP
              )
            """)
    int updateSensitive(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("personId") UUID personId,
            @Param("phoneCiphertext") String phoneCiphertext,
            @Param("phoneMasked") String phoneMasked,
            @Param("certificateCiphertext") String certificateCiphertext,
            @Param("certificateMasked") String certificateMasked,
            @Param("version") long version,
            @Param("operatorId") UUID operatorId
    );

    /** 使用乐观锁归档组织级人员。 */
    @Update("""
            UPDATE mine_person person
            SET status = 'ARCHIVED', archived_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP, updated_by = #{operatorId}, version = version + 1
            WHERE person.organization_id = #{organizationId}
              AND person.id = #{personId}
              AND person.version = #{version}
              AND person.status <> 'ARCHIVED'
              AND EXISTS (
                  SELECT 1 FROM mine_person_site_assignment assignment
                    WHERE assignment.organization_id = person.organization_id
                    AND assignment.person_id = person.id
                    AND assignment.site_id = #{siteId}
                    AND assignment.valid_period @> CURRENT_TIMESTAMP
              )
            """)
    int archivePerson(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("personId") UUID personId,
            @Param("version") long version,
            @Param("operatorId") UUID operatorId
    );

    /** 新增组织级司机资质。 */
    @Insert("""
            INSERT INTO mine_driver_qualification (
                id, organization_id, person_id, qualification_code, name,
                allowed_vehicle_types, valid_from, valid_to, created_by, updated_by
            ) VALUES (
                #{id}, #{organizationId}, #{personId}, #{qualificationCode}, #{name},
                CAST(#{allowedVehicleTypesJson} AS JSONB), #{validFrom}, #{validTo},
                #{operatorId}, #{operatorId}
            )
            """)
    int insertQualification(
            @Param("id") UUID id,
            @Param("organizationId") UUID organizationId,
            @Param("personId") UUID personId,
            @Param("qualificationCode") String qualificationCode,
            @Param("name") String name,
            @Param("allowedVehicleTypesJson") String allowedVehicleTypesJson,
            @Param("validFrom") LocalDate validFrom,
            @Param("validTo") LocalDate validTo,
            @Param("operatorId") UUID operatorId
    );

    /** 查询人员全部组织级司机资质。 */
    @Select("""
            SELECT id, organization_id AS organizationId,
                   person_id AS personId, qualification_code AS qualificationCode,
                   name, allowed_vehicle_types::TEXT AS allowedVehicleTypesJson,
                   valid_from AS validFrom, valid_to AS validTo, status,
                   revoked_at AS revokedAt, created_at AS createdAt,
                   updated_at AS updatedAt, version
            FROM mine_driver_qualification
            WHERE organization_id = #{organizationId}
              AND person_id = #{personId}
            ORDER BY valid_from DESC, id
            """)
    List<MineDriverQualification> findQualifications(
            @Param("organizationId") UUID organizationId,
            @Param("personId") UUID personId
    );

    /** 查询人员单项组织级司机资质。 */
    @Select("""
            SELECT id, organization_id AS organizationId,
                   person_id AS personId, qualification_code AS qualificationCode,
                   name, allowed_vehicle_types::TEXT AS allowedVehicleTypesJson,
                   valid_from AS validFrom, valid_to AS validTo, status,
                   revoked_at AS revokedAt, created_at AS createdAt,
                   updated_at AS updatedAt, version
            FROM mine_driver_qualification
            WHERE organization_id = #{organizationId}
              AND person_id = #{personId}
              AND id = #{qualificationId}
            """)
    Optional<MineDriverQualification> findQualification(
            @Param("organizationId") UUID organizationId,
            @Param("personId") UUID personId,
            @Param("qualificationId") UUID qualificationId
    );

    /** 使用乐观锁吊销组织级司机资质。 */
    @Update("""
            UPDATE mine_driver_qualification
            SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP, updated_by = #{operatorId}, version = version + 1
            WHERE organization_id = #{organizationId}
              AND person_id = #{personId}
              AND id = #{qualificationId}
              AND version = #{version}
              AND status = 'ACTIVE'
            """)
    int revokeQualification(
            @Param("organizationId") UUID organizationId,
            @Param("personId") UUID personId,
            @Param("qualificationId") UUID qualificationId,
            @Param("version") long version,
            @Param("operatorId") UUID operatorId
    );

    /** 锁定一批仍使用一版格式的人员敏感密文。 */
    @Select("""
            SELECT id, phone_ciphertext AS phoneCiphertext,
                   certificate_ciphertext AS certificateCiphertext
            FROM mine_person
            WHERE phone_ciphertext LIKE 'v1:%'
               OR certificate_ciphertext LIKE 'v1:%'
            ORDER BY id
            FOR UPDATE
            LIMIT #{limit}
            """)
    List<LegacyPersonSensitiveData> lockLegacySensitivePeople(@Param("limit") int limit);

    /** 仅在密文未被并发修改时写回二版密文。 */
    @Update("""
            UPDATE mine_person
            SET phone_ciphertext = #{newPhoneCiphertext},
                certificate_ciphertext = #{newCertificateCiphertext}
            WHERE id = #{personId}
              AND phone_ciphertext = #{expectedPhoneCiphertext}
              AND certificate_ciphertext = #{expectedCertificateCiphertext}
            """)
    int upgradeSensitiveCiphertexts(
            @Param("personId") UUID personId,
            @Param("expectedPhoneCiphertext") String expectedPhoneCiphertext,
            @Param("expectedCertificateCiphertext") String expectedCertificateCiphertext,
            @Param("newPhoneCiphertext") String newPhoneCiphertext,
            @Param("newCertificateCiphertext") String newCertificateCiphertext
    );

    /** 统计尚未完成二版升级的人员主档。 */
    @Select("""
            SELECT COUNT(*)
            FROM mine_person
            WHERE phone_ciphertext LIKE 'v1:%'
               OR certificate_ciphertext LIKE 'v1:%'
            """)
    long countLegacySensitivePeople();

    /** 串行化密钥身份建立、校验和旧版升级完成动作。 */
    @Select("SELECT pg_advisory_xact_lock(-487311842020)")
    String lockSensitiveDataKeyGuard();

    /** 锁定人员敏感数据密钥身份哨兵。 */
    @Select("""
            SELECT verification_ciphertext
            FROM mine_sensitive_data_key_guard
            WHERE purpose = 'PERSON_DATA'
            FOR UPDATE
            """)
    Optional<String> findKeyVerificationCiphertext();

    /** 在首次启动时建立人员敏感数据密钥身份哨兵。 */
    @Insert("""
            INSERT INTO mine_sensitive_data_key_guard (
                purpose, verification_ciphertext
            ) VALUES ('PERSON_DATA', #{verificationCiphertext})
            """)
    int insertKeyVerificationCiphertext(
            @Param("verificationCiphertext") String verificationCiphertext
    );

    /** 查询一条现有敏感密文，用于首次建立哨兵前认证密钥身份。 */
    @Select("""
            SELECT id, phone_ciphertext AS phoneCiphertext,
                   certificate_ciphertext AS certificateCiphertext
            FROM mine_person
            ORDER BY id
            LIMIT 1
            """)
    Optional<LegacyPersonSensitiveData> findSensitiveCiphertextSample();

    /** 持久化旧版密文升级完成状态并启用数据库二版写入保护。 */
    @Update("""
            UPDATE mine_sensitive_data_key_guard
            SET legacy_upgrade_completed = TRUE,
                completed_at = COALESCE(completed_at, CURRENT_TIMESTAMP),
                updated_at = CURRENT_TIMESTAMP
            WHERE purpose = 'PERSON_DATA'
            """)
    int markLegacyUpgradeCompleted();
}
