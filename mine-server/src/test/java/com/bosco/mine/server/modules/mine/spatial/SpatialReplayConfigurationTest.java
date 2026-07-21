package com.bosco.mine.server.modules.mine.spatial;

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
 * 校验空间重放连接使用独立密钥、小连接池且不会覆盖业务主数据源。
 */
class SpatialReplayConfigurationTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void 应从独立密钥创建可写但受控的小连接池() throws Exception {
        Path passwordFile = temporaryDirectory.resolve("replay_password");
        Files.writeString(passwordFile, "replay-secret\n");

        SpatialReplayConnection connection = new SpatialReplayConfiguration()
                .spatialReplayConnection(properties(passwordFile));

        try (connection) {
            HikariDataSource dataSource = (HikariDataSource) connection.dataSource();
            assertThat(dataSource.getJdbcUrl()).isEqualTo("jdbc:postgresql://timescale/mine");
            assertThat(dataSource.getUsername()).isEqualTo("mine_ts_replay");
            assertThat(dataSource.getPassword()).isEqualTo("replay-secret");
            assertThat(dataSource.isReadOnly()).isFalse();
            assertThat(dataSource.getMaximumPoolSize()).isEqualTo(2);
            assertThat(dataSource.getMinimumIdle()).isZero();
            assertThat(dataSource.getConnectionInitSql()).contains("statement_timeout", "3000ms");
        }
    }

    @Test
    void 密钥文件不存在时必须拒绝启动() {
        assertThatThrownBy(() -> new SpatialReplayConfiguration()
                .spatialReplayConnection(properties(temporaryDirectory.resolve("missing"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("无法读取空间重放数据库密码文件");
    }

    @Test
    void 启用重放连接后业务库仍应是Spring唯一主数据源() throws Exception {
        Path passwordFile = temporaryDirectory.resolve("context_replay_password");
        Files.writeString(passwordFile, "replay-secret\n");
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        JdbcTemplateAutoConfiguration.class))
                .withUserConfiguration(SpatialReplayConfiguration.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:postgresql://business-db:5432/mine",
                        "spring.datasource.username=mine",
                        "spring.datasource.password=business-secret",
                        "mine.spatial-replay.enabled=true",
                        "mine.spatial-replay.url=jdbc:postgresql://timescale:5432/mine_telemetry",
                        "mine.spatial-replay.username=mine_ts_replay",
                        "mine.spatial-replay.password-file=" + passwordFile,
                        "mine.spatial-replay.maximum-pool-size=2",
                        "mine.spatial-replay.connection-timeout=PT2S",
                        "mine.spatial-replay.statement-timeout=PT3S")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
                    HikariDataSource businessDataSource = context.getBean(HikariDataSource.class);
                    assertThat(businessDataSource.getJdbcUrl())
                            .isEqualTo("jdbc:postgresql://business-db:5432/mine");
                    assertThat(context.getBean(JdbcTemplate.class).getDataSource())
                            .isSameAs(businessDataSource);
                    assertThat(((HikariDataSource) context.getBean(
                            SpatialReplayConnection.class).dataSource()).getJdbcUrl())
                            .contains("timescale:5432/mine_telemetry");
                });
    }

    private SpatialReplayProperties properties(Path passwordFile) {
        return new SpatialReplayProperties(
                true, "jdbc:postgresql://timescale/mine", "mine_ts_replay",
                passwordFile, 2, Duration.ofSeconds(2), Duration.ofSeconds(3));
    }
}
