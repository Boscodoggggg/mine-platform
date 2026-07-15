package com.bosco.mine.server.modules.device.infrastructure;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验遥测查询独立数据源的密钥读取和安全配置。
 */
class TelemetryQueryConfigurationTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void 应从密钥文件创建只读小连接池() throws Exception {
        Path passwordFile = temporaryDirectory.resolve("timescale_password");
        Files.writeString(passwordFile, "telemetry-secret\n");
        TelemetryQueryProperties properties = properties(passwordFile);

        TelemetryQueryConnection result = new TelemetryQueryConfiguration()
                .telemetryQueryConnection(properties);

        try (result) {
            HikariDataSource dataSource = (HikariDataSource) result
                    .jdbcTemplate().getDataSource();
            assertThat(dataSource.getJdbcUrl()).isEqualTo("jdbc:postgresql://timescale/mine");
            assertThat(dataSource.getUsername()).isEqualTo("mine_ts");
            assertThat(dataSource.getPassword()).isEqualTo("telemetry-secret");
            assertThat(dataSource.isReadOnly()).isTrue();
            assertThat(dataSource.getMaximumPoolSize()).isEqualTo(5);
            assertThat(dataSource.getMinimumIdle()).isZero();
            assertThat(result.jdbcTemplate().getQueryTimeout()).isEqualTo(2);
        }
    }

    @Test
    void 密钥文件不存在时必须拒绝启动() {
        TelemetryQueryProperties properties = properties(
                temporaryDirectory.resolve("missing_password")
        );

        assertThatThrownBy(() -> new TelemetryQueryConfiguration()
                .telemetryQueryConnection(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("无法读取遥测查询数据库密码文件");
    }

    @Test
    void 启用遥测查询后业务库仍应是Spring唯一主数据源() throws Exception {
        Path passwordFile = temporaryDirectory.resolve("context_timescale_password");
        Files.writeString(passwordFile, "telemetry-secret\n");
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        JdbcTemplateAutoConfiguration.class
                ))
                .withUserConfiguration(TelemetryQueryConfiguration.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:postgresql://business-db:5432/mine",
                        "spring.datasource.username=mine",
                        "spring.datasource.password=business-secret",
                        "mine.telemetry-query.enabled=true",
                        "mine.telemetry-query.url=jdbc:postgresql://timescale:5432/mine_telemetry",
                        "mine.telemetry-query.username=mine_ts_reader",
                        "mine.telemetry-query.password-file=" + passwordFile,
                        "mine.telemetry-query.maximum-pool-size=5",
                        "mine.telemetry-query.connection-timeout=PT2S",
                        "mine.telemetry-query.query-timeout=PT2S"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
                    HikariDataSource businessDataSource = context.getBean(HikariDataSource.class);
                    assertThat(businessDataSource.getJdbcUrl())
                            .isEqualTo("jdbc:postgresql://business-db:5432/mine");
                    JdbcTemplate businessTemplate = context.getBean(JdbcTemplate.class);
                    assertThat(businessTemplate.getDataSource()).isSameAs(businessDataSource);
                    TelemetryQueryConnection telemetry = context.getBean(
                            TelemetryQueryConnection.class
                    );
                    assertThat(((HikariDataSource) telemetry.jdbcTemplate().getDataSource())
                            .getJdbcUrl()).contains("timescale:5432/mine_telemetry");
                });
    }

    private TelemetryQueryProperties properties(Path passwordFile) {
        return new TelemetryQueryProperties(
                true,
                "jdbc:postgresql://timescale/mine",
                "mine_ts",
                passwordFile,
                5,
                Duration.ofSeconds(2),
                Duration.ofSeconds(2)
        );
    }
}
