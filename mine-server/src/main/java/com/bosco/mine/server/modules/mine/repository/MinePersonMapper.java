package com.bosco.mine.server.modules.mine.repository;

import com.bosco.mine.server.modules.mine.model.MineDriverQualification;
import com.bosco.mine.server.modules.mine.model.MinePerson;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 按组织和矿区双重作用域访问人员与司机资质。
 */
@Mapper
public interface MinePersonMapper {

    /** 插入已加密的人员档案。 */
    @Insert("""
            INSERT INTO mine_person (
                id, organization_id, site_id, person_code, name, person_type,
                phone_ciphertext, phone_masked, certificate_ciphertext, certificate_masked,
                employment_from, employment_to, created_by, updated_by
            ) VALUES (
                #{id}, #{organizationId}, #{siteId}, #{personCode}, #{name}, #{personType},
                #{phoneCiphertext}, #{phoneMasked}, #{certificateCiphertext}, #{certificateMasked},
                #{employmentFrom}, #{employmentTo}, #{operatorId}, #{operatorId}
            )
            """)
    int insertPerson(
            @Param("id") UUID id,
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("personCode") String personCode,
            @Param("name") String name,
            @Param("personType") String personType,
            @Param("phoneCiphertext") String phoneCiphertext,
            @Param("phoneMasked") String phoneMasked,
            @Param("certificateCiphertext") String certificateCiphertext,
            @Param("certificateMasked") String certificateMasked,
            @Param("employmentFrom") LocalDate employmentFrom,
            @Param("employmentTo") LocalDate employmentTo,
            @Param("operatorId") UUID operatorId
    );

    /** 查询作用域内人员档案。 */
    @Select("""
            SELECT id, organization_id AS organizationId, site_id AS siteId,
                   person_code AS personCode, name, person_type AS personType,
                   phone_ciphertext AS phoneCiphertext, phone_masked AS phoneMasked,
                   certificate_ciphertext AS certificateCiphertext,
                   certificate_masked AS certificateMasked,
                   employment_from AS employmentFrom, employment_to AS employmentTo,
                   status, archived_at AS archivedAt, created_at AS createdAt,
                   updated_at AS updatedAt, version
            FROM mine_person
            WHERE organization_id = #{organizationId}
              AND site_id = #{siteId}
              AND id = #{personId}
            """)
    Optional<MinePerson> findPerson(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("personId") UUID personId
    );

    /** 分页查询作用域内人员。 */
    @Select("""
            <script>
            SELECT id, organization_id AS organizationId, site_id AS siteId,
                   person_code AS personCode, name, person_type AS personType,
                   phone_ciphertext AS phoneCiphertext, phone_masked AS phoneMasked,
                   certificate_ciphertext AS certificateCiphertext,
                   certificate_masked AS certificateMasked,
                   employment_from AS employmentFrom, employment_to AS employmentTo,
                   status, archived_at AS archivedAt, created_at AS createdAt,
                   updated_at AS updatedAt, version
            FROM mine_person
            WHERE organization_id = #{organizationId}
              AND site_id = #{siteId}
              <if test="keyword != null">
                AND (person_code ILIKE '%' || #{keyword} || '%' OR name ILIKE '%' || #{keyword} || '%')
              </if>
              <if test="status != null">
                AND status = #{status}
              </if>
            ORDER BY name, id
            OFFSET #{offset} LIMIT #{limit}
            </script>
            """)
    List<MinePerson> findPeople(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    /** 统计作用域内人员。 */
    @Select("""
            <script>
            SELECT COUNT(*)
            FROM mine_person
            WHERE organization_id = #{organizationId}
              AND site_id = #{siteId}
              <if test="keyword != null">
                AND (person_code ILIKE '%' || #{keyword} || '%' OR name ILIKE '%' || #{keyword} || '%')
              </if>
              <if test="status != null">
                AND status = #{status}
              </if>
            </script>
            """)
    long countPeople(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("keyword") String keyword,
            @Param("status") String status
    );

    /** 使用乐观锁修改人员及其加密字段。 */
    @Update("""
            UPDATE mine_person
            SET name = #{name},
                person_type = #{personType},
                phone_ciphertext = #{phoneCiphertext},
                phone_masked = #{phoneMasked},
                certificate_ciphertext = #{certificateCiphertext},
                certificate_masked = #{certificateMasked},
                employment_from = #{employmentFrom},
                employment_to = #{employmentTo},
                status = #{status},
                updated_at = CURRENT_TIMESTAMP,
                updated_by = #{operatorId},
                version = version + 1
            WHERE organization_id = #{organizationId}
              AND site_id = #{siteId}
              AND id = #{personId}
              AND version = #{version}
              AND status <> 'ARCHIVED'
            """)
    int updatePerson(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("personId") UUID personId,
            @Param("name") String name,
            @Param("personType") String personType,
            @Param("phoneCiphertext") String phoneCiphertext,
            @Param("phoneMasked") String phoneMasked,
            @Param("certificateCiphertext") String certificateCiphertext,
            @Param("certificateMasked") String certificateMasked,
            @Param("employmentFrom") LocalDate employmentFrom,
            @Param("employmentTo") LocalDate employmentTo,
            @Param("status") String status,
            @Param("version") long version,
            @Param("operatorId") UUID operatorId
    );

    /** 使用乐观锁归档人员。 */
    @Update("""
            UPDATE mine_person
            SET status = 'ARCHIVED', archived_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP, updated_by = #{operatorId}, version = version + 1
            WHERE organization_id = #{organizationId}
              AND site_id = #{siteId}
              AND id = #{personId}
              AND version = #{version}
              AND status <> 'ARCHIVED'
            """)
    int archivePerson(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("personId") UUID personId,
            @Param("version") long version,
            @Param("operatorId") UUID operatorId
    );

    /** 新增司机资质。 */
    @Insert("""
            INSERT INTO mine_driver_qualification (
                id, organization_id, site_id, person_id, qualification_code, name,
                allowed_vehicle_types, valid_from, valid_to, created_by, updated_by
            ) VALUES (
                #{id}, #{organizationId}, #{siteId}, #{personId}, #{qualificationCode}, #{name},
                CAST(#{allowedVehicleTypesJson} AS JSONB), #{validFrom}, #{validTo},
                #{operatorId}, #{operatorId}
            )
            """)
    int insertQualification(
            @Param("id") UUID id,
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("personId") UUID personId,
            @Param("qualificationCode") String qualificationCode,
            @Param("name") String name,
            @Param("allowedVehicleTypesJson") String allowedVehicleTypesJson,
            @Param("validFrom") LocalDate validFrom,
            @Param("validTo") LocalDate validTo,
            @Param("operatorId") UUID operatorId
    );

    /** 查询人员全部司机资质。 */
    @Select("""
            SELECT id, organization_id AS organizationId, site_id AS siteId,
                   person_id AS personId, qualification_code AS qualificationCode,
                   name, allowed_vehicle_types::TEXT AS allowedVehicleTypesJson,
                   valid_from AS validFrom, valid_to AS validTo, status,
                   revoked_at AS revokedAt, created_at AS createdAt,
                   updated_at AS updatedAt, version
            FROM mine_driver_qualification
            WHERE organization_id = #{organizationId}
              AND site_id = #{siteId}
              AND person_id = #{personId}
            ORDER BY valid_from DESC, id
            """)
    List<MineDriverQualification> findQualifications(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("personId") UUID personId
    );

    /** 查询作用域内单项司机资质。 */
    @Select("""
            SELECT id, organization_id AS organizationId, site_id AS siteId,
                   person_id AS personId, qualification_code AS qualificationCode,
                   name, allowed_vehicle_types::TEXT AS allowedVehicleTypesJson,
                   valid_from AS validFrom, valid_to AS validTo, status,
                   revoked_at AS revokedAt, created_at AS createdAt,
                   updated_at AS updatedAt, version
            FROM mine_driver_qualification
            WHERE organization_id = #{organizationId}
              AND site_id = #{siteId}
              AND person_id = #{personId}
              AND id = #{qualificationId}
            """)
    Optional<MineDriverQualification> findQualification(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("personId") UUID personId,
            @Param("qualificationId") UUID qualificationId
    );

    /** 使用乐观锁吊销司机资质。 */
    @Update("""
            UPDATE mine_driver_qualification
            SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP, updated_by = #{operatorId}, version = version + 1
            WHERE organization_id = #{organizationId}
              AND site_id = #{siteId}
              AND person_id = #{personId}
              AND id = #{qualificationId}
              AND version = #{version}
              AND status = 'ACTIVE'
            """)
    int revokeQualification(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("personId") UUID personId,
            @Param("qualificationId") UUID qualificationId,
            @Param("version") long version,
            @Param("operatorId") UUID operatorId
    );
}
