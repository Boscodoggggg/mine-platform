package com.bosco.mine.server.integration;

import com.bosco.mine.server.MineServerApplication;
import com.bosco.mine.server.modules.command.outbox.OutboxRelayRepository;
import com.bosco.mine.server.modules.mine.location.LocationSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 PostgreSQL 校验定位运行快照的幂等、修订、分页、摘要和事务一致性。
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(
        classes = MineServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class LocationSnapshotPublishingIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("mine")
            .withUsername("mine")
            .withPassword("mine-test-password");

    @Autowired
    private LocationSnapshotService snapshotService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private OutboxRelayRepository outboxRelayRepository;

    private UUID organizationId;
    private UUID siteId;

    @BeforeEach
    void 准备空矿区() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    mine_location_runtime_snapshot_item,
                    mine_location_runtime_snapshot,
                    mine_location_tag,
                    mine_site,
                    mine_organization,
                    sys_outbox_event
                CASCADE
                """);
        organizationId = UUID.randomUUID();
        siteId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_organization (id, organization_code, name)
                VALUES (?, ?, '定位快照测试组织')
                """, organizationId, "snapshot-org-" + shortId(organizationId));
        jdbcTemplate.update("""
                INSERT INTO mine_site (id, organization_id, site_code, name, timezone)
                VALUES (?, ?, ?, '定位快照测试矿区', 'Asia/Shanghai')
                """, siteId, organizationId, "snapshot-site-" + shortId(siteId));
    }

    @Test
    void 同一规范内容必须幂等且修订摘要分页完整() {
        UUID firstTagId = insertTag("snapshot-tag-a", "ACTIVE");
        UUID secondTagId = insertTag("snapshot-tag-b", "LOST");

        LocationSnapshotService.SnapshotView first = snapshotService.captureSite(
                organizationId, siteId, LocationSnapshotService.ChangeReason.ASSET_CHANGE)
                .orElseThrow();

        assertThat(snapshotService.captureSite(
                organizationId, siteId, LocationSnapshotService.ChangeReason.ASSET_CHANGE))
                .isEmpty();
        assertThat(first.revision()).isOne();
        assertThat(first.contentHash()).matches("sha256:[0-9a-f]{64}");
        assertThat(first.itemCount()).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mine_location_runtime_snapshot", Long.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM sys_outbox_event
                WHERE aggregate_type = 'LOCATION_RUNTIME_SNAPSHOT'
                  AND event_type = 'location.runtime.changed'
                  AND (SELECT COUNT(*) FROM jsonb_object_keys(payload)) = 3
                  AND payload ?& ARRAY['snapshotId', 'revision', 'contentHash']
                """, Long.class)).isOne();
        assertThat(outboxRelayRepository.findRecoverableEventIds(10))
                .contains(first.outboxEventId());
        assertThat(outboxRelayRepository.claimById(
                first.outboxEventId(), "location-snapshot-it", Duration.ofSeconds(30)))
                .get()
                .extracting(event -> event.eventType())
                .isEqualTo("location.runtime.changed");

        List<LocationSnapshotService.SnapshotItemView> allItems = new ArrayList<>();
        String cursor = null;
        do {
            LocationSnapshotService.SnapshotPage page = snapshotService.findSnapshotPage(
                    first.snapshotId(), cursor, 1);
            assertThat(page.snapshotId()).isEqualTo(first.snapshotId());
            assertThat(page.revision()).isEqualTo(first.revision());
            assertThat(page.contentHash()).isEqualTo(first.contentHash());
            allItems.addAll(page.items());
            cursor = page.nextAfterItemKey();
        } while (cursor != null);

        assertThat(allItems)
                .extracting(LocationSnapshotService.SnapshotItemView::itemKey)
                .containsExactlyElementsOf(List.of(
                        "LOCATION_TAG:" + firstTagId,
                        "LOCATION_TAG:" + secondTagId,
                        "SITE:" + siteId).stream().sorted().toList());
        assertThat(allItems)
                .extracting(LocationSnapshotService.SnapshotItemView::itemType)
                .containsExactly("LOCATION_TAG", "LOCATION_TAG", "SITE");

        jdbcTemplate.update(
                "UPDATE mine_location_tag SET status = 'ACTIVE' WHERE id = ?", secondTagId);
        LocationSnapshotService.SnapshotView second = snapshotService.captureSite(
                organizationId, siteId, LocationSnapshotService.ChangeReason.ASSET_CHANGE)
                .orElseThrow();
        assertThat(second.revision()).isEqualTo(2);
        assertThat(second.contentHash()).isNotEqualTo(first.contentHash());

        jdbcTemplate.update(
                "UPDATE mine_location_tag SET status = 'LOST' WHERE id = ?", secondTagId);
        LocationSnapshotService.SnapshotView reverted = snapshotService.captureSite(
                organizationId, siteId, LocationSnapshotService.ChangeReason.ASSET_CHANGE)
                .orElseThrow();
        assertThat(reverted.revision()).isEqualTo(3);
        assertThat(reverted.contentHash()).isEqualTo(first.contentHash());
    }

    @Test
    void 快照必须不可变且外层事务回滚不得留下快照或Outbox() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            insertTag("rolled-back-tag", "ACTIVE");
            assertThat(snapshotService.captureSite(
                    organizationId, siteId, LocationSnapshotService.ChangeReason.ASSET_CHANGE))
                    .isPresent();
            status.setRollbackOnly();
        });

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mine_location_runtime_snapshot", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_outbox_event
                WHERE event_type = 'location.runtime.changed'
                """, Long.class)).isZero();

        LocationSnapshotService.SnapshotView committed = snapshotService.captureSite(
                organizationId, siteId, LocationSnapshotService.ChangeReason.SITE_CHANGE)
                .orElseThrow();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE mine_location_runtime_snapshot
                SET content_hash = ?
                WHERE id = ?
                """, "sha256:" + "0".repeat(64), committed.snapshotId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                DELETE FROM mine_location_runtime_snapshot_item
                WHERE snapshot_id = ?
                """, committed.snapshotId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID insertTag(String code, String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_location_tag (
                    id, organization_id, site_id, tag_code, status, archived_at
                ) VALUES (
                    ?, ?, ?, ?, ?,
                    CASE WHEN ? = 'ARCHIVED' THEN CURRENT_TIMESTAMP ELSE NULL END
                )
                """, id, organizationId, siteId,
                code + "-" + shortId(id), status, status);
        return id;
    }

    private String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }
}
