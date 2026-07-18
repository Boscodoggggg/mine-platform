package com.bosco.mine.telemetry.location.snapshot;

import com.bosco.mine.messaging.location.LocationRuntimeChangedPayload;
import com.bosco.mine.telemetry.location.transform.CoordinateTransformRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class LocationSnapshotLoaderIT {

    private static final DockerImageName TIMESCALE_IMAGE = DockerImageName
            .parse("timescale/timescaledb:2.28.2-pg18-oss@sha256:"
                    + "8ed8d7336f00a3a90bb6fe709d506172012c7e673e0cf71465491d06e2db5fbd")
            .asCompatibleSubstituteFor("postgres");
    private static final UUID ORGANIZATION_ID = uuid(100);
    private static final UUID SITE_ID = uuid(101);
    private static final UUID SOURCE_COORDINATE_ID = uuid(102);
    private static final UUID TARGET_COORDINATE_ID = uuid(103);
    private static final UUID TRANSFORM_ID = uuid(104);
    private static final UUID DEVICE_ID = uuid(105);
    private static final UUID TAG_ID = uuid(106);
    private static final UUID VEHICLE_ID = uuid(107);
    private static final UUID DEVICE_BINDING_ID = uuid(108);
    private static final UUID TAG_BINDING_ID = uuid(109);
    private static final UUID EVENT_ID = uuid(110);
    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

    @Container
    static final PostgreSQLContainer TIMESCALE = new PostgreSQLContainer(TIMESCALE_IMAGE)
            .withDatabaseName("mine_location_snapshot")
            .withUsername("mine_ts")
            .withPassword("mine-location-snapshot-test-password");

    @Test
    void 应只激活完整更高修订并在重启后恢复最后正确快照() {
        var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                TIMESCALE.getJdbcUrl(), TIMESCALE.getUsername(), TIMESCALE.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .load().migrate();

        LocationRuntimeRegistry registry = new LocationRuntimeRegistry(
                dataSource, new CoordinateTransformRegistry());
        SnapshotFixture revisionThree = fixture(uuid(201), 3, validMatrix(), 0);
        new LocationSnapshotLoader(registry, revisionThree.source(), 3).apply(
                EVENT_ID, SITE_ID, revisionThree.event());

        assertThat(registry.current(SITE_ID)).get()
                .satisfies(snapshot -> {
                    assertThat(snapshot.snapshotId()).isEqualTo(revisionThree.snapshotId());
                    assertThat(snapshot.revision()).isEqualTo(3);
                    assertThat(snapshot.items()).hasSize(revisionThree.items().size());
                });
        assertThat(query(dataSource, """
                SELECT runtime_revision || ':' || item_count
                FROM location_runtime_revision
                WHERE site_id = '%s'
                """.formatted(SITE_ID))).containsExactly("3:" + revisionThree.items().size());

        LocationRuntimeRegistry restored = new LocationRuntimeRegistry(
                dataSource, new CoordinateTransformRegistry());
        restored.restore();
        assertThat(restored.current(SITE_ID)).get()
                .extracting(LocationRuntimeRegistry.RuntimeSnapshot::snapshotId)
                .isEqualTo(revisionThree.snapshotId());

        SnapshotFixture invalidTransform = fixture(uuid(202), 4, singularMatrix(), 0);
        assertThatThrownBy(() -> new LocationSnapshotLoader(
                registry, invalidTransform.source(), 4).apply(
                uuid(203), SITE_ID, invalidTransform.event()))
                .isInstanceOf(PermanentLocationSnapshotException.class)
                .hasMessageContaining("坐标转换");
        assertThat(registry.current(SITE_ID)).get()
                .extracting(LocationRuntimeRegistry.RuntimeSnapshot::revision)
                .isEqualTo(3L);

        SnapshotFixture missingItem = fixture(uuid(204), 5, validMatrix(), 1);
        assertThatThrownBy(() -> new LocationSnapshotLoader(
                registry, missingItem.source(), 3).apply(
                uuid(205), SITE_ID, missingItem.event()))
                .isInstanceOf(PermanentLocationSnapshotException.class)
                .hasMessageContaining("数量");

        SnapshotFixture badDigest = fixture(uuid(212), 5, validMatrix(), 0);
        LocationRuntimeChangedPayload tamperedEvent = new LocationRuntimeChangedPayload(
                badDigest.snapshotId(), badDigest.revision(), "sha256:" + "f".repeat(64));
        assertThatThrownBy(() -> new LocationSnapshotLoader(
                registry, badDigest.source(), 3).apply(
                uuid(213), SITE_ID, tamperedEvent))
                .isInstanceOf(PermanentLocationSnapshotException.class)
                .hasMessageContaining("快照头");

        SnapshotFixture lowerRevision = fixture(uuid(206), 2, validMatrix(), 0);
        new LocationSnapshotLoader(registry, lowerRevision.source(), 3).apply(
                uuid(207), SITE_ID, lowerRevision.event());
        assertThat(registry.current(SITE_ID)).get()
                .extracting(LocationRuntimeRegistry.RuntimeSnapshot::revision)
                .isEqualTo(3L);

        SnapshotFixture sameRevisionDifferentSnapshot =
                fixture(uuid(208), 3, validMatrix(), 0);
        assertThatThrownBy(() -> new LocationSnapshotLoader(
                registry, sameRevisionDifferentSnapshot.source(), 3).apply(
                uuid(209), SITE_ID, sameRevisionDifferentSnapshot.event()))
                .isInstanceOf(PermanentLocationSnapshotException.class)
                .hasMessageContaining("相同修订");

        SnapshotFixture unavailable = fixture(uuid(210), 6, validMatrix(), 0);
        LocationSnapshotSource unavailableSource = (snapshotId, cursor, limit) -> {
            throw new IllegalStateException("server unavailable");
        };
        assertThatThrownBy(() -> new LocationSnapshotLoader(
                registry, unavailableSource, 3).apply(
                uuid(211), SITE_ID, unavailable.event()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unavailable");
        assertThat(registry.current(SITE_ID)).get()
                .extracting(LocationRuntimeRegistry.RuntimeSnapshot::snapshotId)
                .isEqualTo(revisionThree.snapshotId());
    }

    private SnapshotFixture fixture(
            UUID snapshotId,
            long revision,
            List<Double> matrix,
            int declaredExtraItems
    ) {
        List<ItemFixture> items = new ArrayList<>();
        items.add(item("COORDINATE_SYSTEM:" + SOURCE_COORDINATE_ID, "COORDINATE_SYSTEM",
                Map.ofEntries(
                        Map.entry("coordinateSystemId", SOURCE_COORDINATE_ID),
                        Map.entry("coordinateType", "LOCAL_CARTESIAN"),
                        Map.entry("unit", "MILLIMETER"), Map.entry("unified", false),
                        Map.entry("status", "ACTIVE"), Map.entry("minX", -1_000_000),
                        Map.entry("maxX", 1_000_000), Map.entry("minY", -1_000_000),
                        Map.entry("maxY", 1_000_000), Map.entry("minZ", -10_000),
                        Map.entry("maxZ", 10_000))));
        items.add(item("COORDINATE_SYSTEM:" + TARGET_COORDINATE_ID, "COORDINATE_SYSTEM",
                Map.ofEntries(
                        Map.entry("coordinateSystemId", TARGET_COORDINATE_ID),
                        Map.entry("coordinateType", "LOCAL_CARTESIAN"),
                        Map.entry("unit", "METER"), Map.entry("unified", true),
                        Map.entry("status", "ACTIVE"), Map.entry("minX", -1_000),
                        Map.entry("maxX", 1_000), Map.entry("minY", -1_000),
                        Map.entry("maxY", 1_000), Map.entry("minZ", -100),
                        Map.entry("maxZ", 100))));
        items.add(item("COORDINATE_TRANSFORM:" + TRANSFORM_ID, "COORDINATE_TRANSFORM", Map.of(
                "coordinateTransformId", TRANSFORM_ID,
                "sourceCoordinateSystemId", SOURCE_COORDINATE_ID,
                "targetCoordinateSystemId", TARGET_COORDINATE_ID,
                "versionNumber", 1, "algorithm", "AFFINE_3D",
                "parameters", Map.of("matrix", matrix),
                "validFrom", "2026-01-01T00:00:00Z")));
        items.add(item("LOCATION_TAG:" + TAG_ID, "LOCATION_TAG", Map.of(
                "tagId", TAG_ID, "tagCode", "uwb-001", "status", "ACTIVE")));
        items.add(item("SITE:" + SITE_ID, "SITE", Map.of(
                "organizationId", ORGANIZATION_ID, "siteId", SITE_ID,
                "siteCode", "north-mine", "status", "ACTIVE", "timezone", "Asia/Shanghai")));
        items.add(item("SOURCE_DEVICE:" + DEVICE_ID, "SOURCE_DEVICE", Map.of(
                "deviceId", DEVICE_ID, "status", "ACTIVE")));
        items.add(item("VEHICLE:" + VEHICLE_ID, "VEHICLE", Map.of(
                "vehicleId", VEHICLE_ID, "vehicleType", "HAUL_TRUCK", "status", "ACTIVE")));
        items.add(item("VEHICLE_DEVICE_BINDING:" + DEVICE_BINDING_ID,
                "VEHICLE_DEVICE_BINDING", Map.of(
                        "bindingId", DEVICE_BINDING_ID, "deviceId", DEVICE_ID,
                        "vehicleId", VEHICLE_ID, "role", "POSITION_SOURCE",
                        "validFrom", "2026-01-01T00:00:00Z")));
        items.add(item("VEHICLE_TAG_BINDING:" + TAG_BINDING_ID,
                "VEHICLE_TAG_BINDING", Map.of(
                        "bindingId", TAG_BINDING_ID, "tagId", TAG_ID,
                        "vehicleId", VEHICLE_ID,
                        "validFrom", "2026-01-01T00:00:00Z")));
        items.sort(Comparator.comparing(ItemFixture::itemKey));
        String snapshotHash = snapshotHash(items);
        int declaredCount = items.size() + declaredExtraItems;
        LocationSnapshotSource source = new PagedSource(
                snapshotId, revision, declaredCount, snapshotHash, List.copyOf(items));
        return new SnapshotFixture(
                snapshotId, revision, List.copyOf(items), source,
                new LocationRuntimeChangedPayload(snapshotId, revision, snapshotHash));
    }

    private ItemFixture item(String key, String type, Map<String, Object> content) {
        String canonical = canonicalJson(content);
        return new ItemFixture(key, type, readJson(canonical), hash(canonical));
    }

    private String snapshotHash(List<ItemFixture> items) {
        StringBuilder value = new StringBuilder();
        items.forEach(item -> value.append(item.itemKey()).append(':')
                .append(item.contentHash()).append('\n'));
        return hash(value.toString());
    }

    private String canonicalJson(Object value) {
        try {
            return JSON.writeValueAsString(canonicalize(value));
        } catch (JacksonException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonicalize(item)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::canonicalize).toList();
        }
        return value;
    }

    private JsonNode readJson(String value) {
        try {
            return JSON.readTree(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String hash(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private List<String> query(javax.sql.DataSource dataSource, String sql) {
        return new org.springframework.jdbc.core.JdbcTemplate(dataSource)
                .query(sql, (resultSet, row) -> resultSet.getString(1));
    }

    private List<Double> validMatrix() {
        return List.of(
                0.001D, 0D, 0D, 0D,
                0D, 0.001D, 0D, 0D,
                0D, 0D, 0.001D, 0D,
                0D, 0D, 0D, 1D);
    }

    private List<Double> singularMatrix() {
        return List.of(
                0.001D, 0D, 0D, 0D,
                0D, 0D, 0D, 0D,
                0D, 0D, 0.001D, 0D,
                0D, 0D, 0D, 1D);
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("019f7100-0000-7000-8000-%012d".formatted(suffix));
    }

    private record SnapshotFixture(
            UUID snapshotId,
            long revision,
            List<ItemFixture> items,
            LocationSnapshotSource source,
            LocationRuntimeChangedPayload event
    ) {
    }

    private record ItemFixture(
            String itemKey,
            String itemType,
            JsonNode content,
            String contentHash
    ) {
    }

    private final class PagedSource implements LocationSnapshotSource {
        private final UUID snapshotId;
        private final long revision;
        private final int itemCount;
        private final String contentHash;
        private final List<ItemFixture> items;

        private PagedSource(
                UUID snapshotId,
                long revision,
                int itemCount,
                String contentHash,
                List<ItemFixture> items
        ) {
            this.snapshotId = snapshotId;
            this.revision = revision;
            this.itemCount = itemCount;
            this.contentHash = contentHash;
            this.items = items;
        }

        @Override
        public SnapshotPage fetch(UUID requestedSnapshotId, String afterItemKey, int limit) {
            int start = 0;
            if (afterItemKey != null) {
                while (start < items.size()
                        && items.get(start).itemKey().compareTo(afterItemKey) <= 0) {
                    start++;
                }
            }
            int end = Math.min(start + limit, items.size());
            List<SnapshotItem> pageItems = items.subList(start, end).stream()
                    .map(item -> new SnapshotItem(
                            item.itemKey(), item.itemType(), item.content(), item.contentHash()))
                    .toList();
            String next = end < items.size() ? items.get(end - 1).itemKey() : null;
            return new SnapshotPage(
                    snapshotId, ORGANIZATION_ID, SITE_ID, revision, itemCount,
                    contentHash, Instant.parse("2026-07-18T00:00:00Z"), pageItems, next);
        }
    }
}
