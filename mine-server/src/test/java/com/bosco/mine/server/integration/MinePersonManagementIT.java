package com.bosco.mine.server.integration;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.MineServerApplication;
import com.bosco.mine.server.modules.mine.controller.MinePersonController;
import com.bosco.mine.server.modules.mine.dto.DriverQualificationRequest;
import com.bosco.mine.server.modules.mine.dto.DriverQualificationResponse;
import com.bosco.mine.server.modules.mine.dto.PersonCreateRequest;
import com.bosco.mine.server.modules.mine.dto.PersonResponse;
import com.bosco.mine.server.modules.mine.dto.PersonSensitiveResponse;
import com.bosco.mine.server.modules.mine.dto.MineOrganizationResponse;
import com.bosco.mine.server.modules.mine.service.MinePersonService;
import com.bosco.mine.server.modules.mine.service.MineRequestIdentity;
import com.bosco.mine.server.modules.mine.service.MineScopeCommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 PostgreSQL 校验矿区人员、司机资质和敏感字段保护闭环。
 */
@Testcontainers
@ActiveProfiles("test")
@Import(MinePersonManagementIT.IdentityTestConfiguration.class)
@SpringBootTest(
        classes = MineServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class MinePersonManagementIT {

    private static final Path KEY_FILE = createKeyFile();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("mine")
            .withUsername("mine")
            .withPassword("mine-test-password");

    @Autowired
    private MinePersonService personService;

    @Autowired
    private MineScopeCommandService scopeCommandService;

    @Autowired
    private MutableMineRequestIdentity identity;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID userId;
    private UUID organizationId;
    private UUID firstSiteId;
    private UUID secondSiteId;

    @DynamicPropertySource
    static void sensitiveKey(DynamicPropertyRegistry registry) {
        registry.add("mine.sensitive-data.key-file", KEY_FILE::toString);
    }

    @BeforeEach
    void 准备同组织两个矿区() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    mine_driver_qualification,
                    mine_person,
                    mine_user_scope_grant,
                    mine_site,
                    mine_organization,
                    sys_operation_log
                CASCADE
                """);
        jdbcTemplate.update("DELETE FROM sys_user WHERE username LIKE 'mine-person-it-%'");
        userId = createUser();
        identity.use(userId);
        MineOrganizationResponse organization = scopeCommandService.createOrganization(
                "person-group", "人员测试集团");
        organizationId = organization.id();
        firstSiteId = scopeCommandService.createSite(
                organizationId, "north-pit", "北方矿区", "Asia/Shanghai", null).id();
        secondSiteId = scopeCommandService.createSite(
                organizationId, "south-pit", "南方矿区", "Asia/Shanghai", null).id();
    }

    @Test
    void 人员编码按组织唯一且任职区间由服务和数据库共同约束() {
        PersonResponse created = personService.createPerson(firstSiteId, personRequest("driver-001"));

        assertThat(created.phoneMasked()).isEqualTo("138****8000");
        assertThat(created.certificateMasked()).doesNotContain("19890101");
        assertThat(personService.findPeople(firstSiteId, null, null, 1, 20).items())
                .extracting(PersonResponse::id)
                .containsExactly(created.id());
        assertThatThrownBy(() -> personService.createPerson(secondSiteId, personRequest("driver-001")))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("MINE-PERSON-409-001"));

        PersonCreateRequest invalid = new PersonCreateRequest(
                "driver-002", "无效司机", "DRIVER", "13900139000",
                "410105199001011212", LocalDate.parse("2026-12-31"),
                LocalDate.parse("2026-01-01"));
        assertThatThrownBy(() -> personService.createPerson(firstSiteId, invalid))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("MINE-PERSON-422-001"));

        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE mine_person SET employment_to = employment_from - 1 WHERE id = ?
                """, created.id())).isInstanceOf(RuntimeException.class);
    }

    @Test
    void 司机资质必须约束允许车型和有效期() {
        PersonResponse person = personService.createPerson(firstSiteId, personRequest("driver-002"));
        DriverQualificationResponse qualification = personService.addQualification(
                firstSiteId,
                person.id(),
                new DriverQualificationRequest(
                        "driver-license-a2", "机动车驾驶资质", List.of("HAUL_TRUCK", "LIGHT_VEHICLE"),
                        LocalDate.parse("2025-01-01"), LocalDate.parse("2028-12-31")));

        assertThat(qualification.allowedVehicleTypes())
                .containsExactly("HAUL_TRUCK", "LIGHT_VEHICLE");
        assertThat(personService.findQualifications(firstSiteId, person.id()))
                .extracting(DriverQualificationResponse::id)
                .containsExactly(qualification.id());

        assertThatThrownBy(() -> personService.addQualification(
                firstSiteId,
                person.id(),
                new DriverQualificationRequest(
                        "invalid-type", "非法车型", List.of("SPACESHIP"),
                        LocalDate.parse("2025-01-01"), LocalDate.parse("2028-01-01"))))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("MINE-PERSON-422-002"));
        assertThatThrownBy(() -> personService.addQualification(
                firstSiteId,
                person.id(),
                new DriverQualificationRequest(
                        "invalid-period", "无效期限", List.of("HAUL_TRUCK"),
                        LocalDate.parse("2028-01-01"), LocalDate.parse("2027-01-01"))))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("MINE-PERSON-422-002"));
    }

    @Test
    void 常规响应只脱敏而敏感读取必须独立授权并记录无明文审计() throws Exception {
        PersonResponse person = personService.createPerson(firstSiteId, personRequest("driver-003"));
        String phoneCiphertext = jdbcTemplate.queryForObject(
                "SELECT phone_ciphertext FROM mine_person WHERE id = ?", String.class, person.id());
        String certificateCiphertext = jdbcTemplate.queryForObject(
                "SELECT certificate_ciphertext FROM mine_person WHERE id = ?", String.class, person.id());
        assertThat(phoneCiphertext).startsWith("v1:").doesNotContain("13800138000");
        assertThat(certificateCiphertext).startsWith("v1:").doesNotContain("410105198901011234");

        PersonSensitiveResponse sensitive = personService.readSensitive(firstSiteId, person.id());
        assertThat(sensitive.phone()).isEqualTo("13800138000");
        assertThat(sensitive.certificateNumber()).isEqualTo("410105198901011234");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_operation_log
                WHERE action = 'READ_PERSON_SENSITIVE'
                  AND permission = 'mine:person:sensitive:view'
                  AND resource_id = ?
                """, Long.class, person.id().toString())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_operation_log
                WHERE COALESCE(before_summary::TEXT, '') || COALESCE(after_summary::TEXT, '')
                      LIKE '%13800138000%'
                   OR COALESCE(before_summary::TEXT, '') || COALESCE(after_summary::TEXT, '')
                      LIKE '%410105198901011234%'
                """, Long.class)).isZero();

        Method method = MinePersonController.class.getMethod(
                "readSensitive", UUID.class, UUID.class);
        assertThat(method.getAnnotation(GetMapping.class).value())
                .containsExactly("/people/{personId}/sensitive");
        assertThat(method.getAnnotation(SaCheckPermission.class).value())
                .containsExactly("mine:person:sensitive:view");
        assertThat(MinePersonController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/mine/sites/{siteId}");
    }

    @Test
    void 人员与资质表和全部字段必须有中文注释() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT obj_description(class.oid, 'pg_class')
                FROM pg_class class
                WHERE class.relname IN ('mine_person', 'mine_driver_qualification')
                ORDER BY class.relname
                """, String.class))
                .hasSize(2)
                .allMatch(comment -> comment.matches(".*[\\u4e00-\\u9fff].*"));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_attribute
                WHERE attrelid IN ('mine_person'::regclass, 'mine_driver_qualification'::regclass)
                  AND attnum > 0
                  AND NOT attisdropped
                  AND COALESCE(col_description(attrelid, attnum), '') = ''
                """, Long.class)).isZero();
    }

    private PersonCreateRequest personRequest(String personCode) {
        return new PersonCreateRequest(
                personCode, "张师傅", "DRIVER", "13800138000",
                "410105198901011234", LocalDate.parse("2025-01-01"), null);
    }

    private UUID createUser() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO sys_user (id, username, password_hash, display_name)
                VALUES (?, ?, 'bcrypt:test', '矿区人员测试用户')
                """, id, "mine-person-it-" + id);
        return id;
    }

    private static Path createKeyFile() {
        try {
            Path path = Files.createTempFile("mine-person-key-", ".secret");
            byte[] key = new byte[32];
            java.util.Arrays.fill(key, (byte) 23);
            Files.writeString(path, Base64.getEncoder().encodeToString(key));
            path.toFile().deleteOnExit();
            return path;
        } catch (java.io.IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    /** 为集成测试提供不依赖 Redis 会话的稳定请求身份。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IdentityTestConfiguration {

        /** 返回可切换的测试身份。 */
        @Bean
        @Primary
        MutableMineRequestIdentity mutableMineRequestIdentity() {
            return new MutableMineRequestIdentity();
        }
    }

    /** 单线程集成测试使用的可切换身份。 */
    static final class MutableMineRequestIdentity implements MineRequestIdentity {
        private UUID userId;

        /** 切换当前测试用户。 */
        void use(UUID value) {
            userId = value;
        }

        /** 返回当前测试用户。 */
        @Override
        public UUID currentUserId() {
            return userId;
        }
    }
}
