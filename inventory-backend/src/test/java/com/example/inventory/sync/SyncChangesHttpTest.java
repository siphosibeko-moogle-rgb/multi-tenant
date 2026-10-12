package com.example.inventory.sync;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.example.inventory.AbstractIntegrationTest;
import com.example.inventory.auth.HttpTestClient;
import com.example.inventory.auth.JwtService;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GET /sync/changes")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SyncChangesHttpTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    private static UUID tenantId;
    private static UUID userId;
    private static UUID locationId;
    private static UUID categoryId;
    private static UUID productId;
    private static boolean seeded;

    @BeforeEach
    void seed() throws SQLException {
        if (seeded) {
            return;
        }
        tenantId = newTenantId();
        userId = UUID.randomUUID();
        locationId = UUID.randomUUID();
        categoryId = UUID.randomUUID();
        productId = UUID.randomUUID();
        String tag = tenantId.toString().substring(0, 8);

        try (Connection conn = owner(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO tenants (id, slug, name) VALUES ('%s', 'sync-%s', 'Sync Shop')"
                    .formatted(tenantId, tag));
            stmt.execute("""
                    INSERT INTO users (id, tenant_id, email, full_name, role, status)
                    VALUES ('%s', '%s', 'owner-%s@example.test', 'Sy Owner', 'owner', 'active')
                    """.formatted(userId, tenantId, tag));
            stmt.execute("INSERT INTO locations (id, tenant_id, name, is_default) "
                    + "VALUES ('%s', '%s', 'Main', true)".formatted(locationId, tenantId));
            stmt.execute("INSERT INTO categories (id, tenant_id, name) "
                    + "VALUES ('%s', '%s', 'Bakery')".formatted(categoryId, tenantId));
            stmt.execute("""
                    INSERT INTO products (id, tenant_id, sku, name, category_id,
                                          cost_price, selling_price, tax_rate)
                    VALUES ('%s', '%s', 'SYNC-1-%s', 'Synced Scone', '%s', 2.00, 6.00, 0.10)
                    """.formatted(productId, tenantId, tag, categoryId));
        }
        seeded = true;
    }

    private static Connection owner() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private HttpTestClient http() {
        return new HttpTestClient(port);
    }

    private String token() {
        return jwtService.issueAccessToken(tenantId, userId, "owner").value();
    }

    private JsonNode sync(String since) {
        String path = since == null ? "/sync/changes" : "/sync/changes?since=" + since;
        HttpTestClient.Response response = http().get(path, token());
        assertThat(response.status()).as("%s -> %s", path, response.body()).isEqualTo(200);
        return response.json();
    }

    private static String tokenOf(JsonNode body) {
        assertThat(body.has("syncToken")).as("syncToken is required by the contract").isTrue();
        return body.get("syncToken").asString();
    }

    private static List<String> keysIn(JsonNode body) {
        List<String> keys = new ArrayList<>();
        for (String array : List.of("products", "categories", "suppliers", "locations")) {
            for (JsonNode row : body.get(array)) {
                keys.add(array + ":" + row.get("id").asString());
            }
        }
        for (JsonNode row : body.get("stock")) {
            keys.add("stock:" + row.get("productId").asString()
                    + ":" + row.get("locationId").asString());
        }
        for (JsonNode row : body.get("deleted")) {
            keys.add("deleted:" + row.get("entityType").asString()
                    + ":" + row.get("id").asString());
        }
        return keys;
    }

    private void renameProduct(UUID id, String name) throws SQLException {
        try (Connection conn = owner(); Statement stmt = conn.createStatement()) {
            stmt.execute("UPDATE products SET name = '%s' WHERE id = '%s'".formatted(name, id));
        }
    }

    @Test
    @DisplayName("a first sync returns a full snapshot and every array the contract requires")
    void fullSnapshot() {
        JsonNode body = sync(null);

        for (String field : List.of("syncToken", "hasMore", "products", "categories",
                "suppliers", "locations", "stock", "deleted")) {
            assertThat(body.has(field)).as("%s is required by the contract", field).isTrue();
            assertThat(body.get(field).isNull()).as("%s must not be null", field).isFalse();
        }

        assertThat(idsOf(body, "products")).contains(productId.toString());
        assertThat(idsOf(body, "categories")).contains(categoryId.toString());
        assertThat(idsOf(body, "locations"))
                .as("locations were absent from the feed until M8 added them; a "
                        + "client cannot record an offline sale without one")
                .contains(locationId.toString());
        assertThat(tokenOf(body)).isNotBlank();
    }

    private static List<String> idsOf(JsonNode body, String array) {
        List<String> ids = new ArrayList<>();
        for (JsonNode row : body.get(array)) {
            ids.add(row.get("id").asString());
        }
        return ids;
    }

    @Test
    @DisplayName("two sequential syncs never return the same row twice")
    void twoSequentialSyncsNeverRepeatARow() {
        JsonNode first = sync(null);
        assertThat(keysIn(first))
                .as("the fixture must deliver something on the first sync, or the "
                        + "emptiness below proves nothing")
                .isNotEmpty();

        JsonNode second = sync(tokenOf(first));

        assertThat(keysIn(second))
                .as("a second sync with nothing changed must deliver nothing. Rows "
                        + "here mean the watermark is not excluding what it already "
                        + "sent — a client would re-apply the catalogue on every poll.")
                .isEmpty();
        assertThat(second.get("hasMore").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("a change made between two syncs is delivered by the second")
    void aChangeBetweenSyncsIsNotMissed() throws SQLException {
        JsonNode first = sync(null);
        String watermark = tokenOf(first);

        renameProduct(productId, "Renamed Between Syncs");

        JsonNode second = sync(watermark);

        assertThat(idsOf(second, "products"))
                .as("the rename happened strictly after the first sync and must "
                        + "appear in the second")
                .contains(productId.toString());

        JsonNode renamed = null;
        for (JsonNode row : second.get("products")) {
            if (row.get("id").asString().equals(productId.toString())) {
                renamed = row;
            }
        }
        assertThat(renamed).isNotNull();
        assertThat(renamed.get("name").asString()).isEqualTo("Renamed Between Syncs");

        assertThat(keysIn(sync(tokenOf(second)))).isEmpty();
    }

    @Test
    @DisplayName("interleaved writes and syncs lose nothing and repeat nothing")
    void interleavedWritesAndSyncsLoseNothingAndRepeatNothing() throws SQLException {
        String watermark = tokenOf(sync(null));
        assertThat(keysIn(sync(watermark))).isEmpty();

        List<UUID> written = new ArrayList<>();
        List<String> delivered = new ArrayList<>();

        for (int round = 0; round < 6; round++) {
            for (int i = 0; i < 2; i++) {
                UUID id = UUID.randomUUID();
                insertProduct(id, "LOOP-" + round + "-" + i);
                written.add(id);
            }

            JsonNode body = sync(watermark);
            for (JsonNode row : body.get("products")) {
                delivered.add(row.get("id").asString());
            }
            watermark = tokenOf(body);
        }

        for (int i = 0; i < 3; i++) {
            JsonNode body = sync(watermark);
            for (JsonNode row : body.get("products")) {
                delivered.add(row.get("id").asString());
            }
            watermark = tokenOf(body);
        }

        Set<String> expected = new HashSet<>();
        written.forEach(id -> expected.add(id.toString()));

        assertThat(new HashSet<>(delivered))
                .as("every product written during the run must have been delivered")
                .containsAll(expected);

        assertThat(delivered)
                .as("a product delivered twice means two syncs covered the same "
                        + "range: %s", delivered)
                .doesNotHaveDuplicates();
    }

    private void insertProduct(UUID id, String sku) throws SQLException {
        try (Connection conn = owner(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    INSERT INTO products (id, tenant_id, sku, name, cost_price, selling_price)
                    VALUES ('%s', '%s', '%s-%s', 'Loop Product', 1.00, 2.00)
                    """.formatted(id, tenantId, sku, id.toString().substring(0, 8)));
        }
    }

    @Test
    @DisplayName("a soft-deleted product arrives as a tombstone, not as a row")
    void deletionsArriveAsTombstones() throws SQLException {
        UUID doomed = UUID.randomUUID();
        insertProduct(doomed, "DOOMED");

        String watermark = tokenOf(sync(null));

        try (Connection conn = owner(); Statement stmt = conn.createStatement()) {
            stmt.execute("UPDATE products SET deleted_at = now() WHERE id = '%s'"
                    .formatted(doomed));
        }

        JsonNode body = sync(watermark);

        assertThat(idsOf(body, "products"))
                .as("a deleted product must not arrive as a live row")
                .doesNotContain(doomed.toString());

        List<String> tombstones = new ArrayList<>();
        for (JsonNode row : body.get("deleted")) {
            tombstones.add(row.get("entityType").asString() + ":" + row.get("id").asString());
        }
        assertThat(tombstones)
                .as("without the tombstone the product stays sellable on that "
                        + "device forever: %s", body.get("deleted"))
                .contains("product:" + doomed.toString());
    }

    @Test
    @DisplayName("stock movements reach the feed, so an offline cache can price a sale")
    void stockChangesAreDelivered() {
        String watermark = tokenOf(sync(null));

        HttpTestClient.Response adjusted = http().postWithToken("/inventory/adjustments", """
                {"productId":"%s","quantityDelta":17,"reason":"opening"}
                """.formatted(productId), token());
        assertThat(adjusted.status()).isEqualTo(201);

        JsonNode body = sync(watermark);

        JsonNode stockRow = null;
        for (JsonNode row : body.get("stock")) {
            if (row.get("productId").asString().equals(productId.toString())) {
                stockRow = row;
            }
        }
        assertThat(stockRow).as("stock did not reach the feed: %s", body.get("stock")).isNotNull();
        assertThat(stockRow.get("quantityOnHand").decimalValue()).isEqualByComparingTo("17");
        assertThat(stockRow.get("locationName").asString()).isEqualTo("Main");
    }

    @Test
    @DisplayName("another tenant's changes never appear in this tenant's feed")
    void tenantIsolation() throws SQLException {
        UUID otherTenant = newTenantId();
        UUID otherProduct = UUID.randomUUID();
        String tag = otherTenant.toString().substring(0, 8);

        String watermark = tokenOf(sync(null));

        try (Connection conn = owner(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO tenants (id, slug, name) VALUES ('%s', 'other-%s', 'Other')"
                    .formatted(otherTenant, tag));
            stmt.execute("""
                    INSERT INTO products (id, tenant_id, sku, name, cost_price, selling_price)
                    VALUES ('%s', '%s', 'OTHER-%s', 'Not Yours', 1.00, 2.00)
                    """.formatted(otherProduct, otherTenant, tag));
        }

        JsonNode body = sync(watermark);

        assertThat(idsOf(body, "products")).doesNotContain(otherProduct.toString());
        assertThat(keysIn(body))
                .as("nothing at all should have changed for THIS tenant")
                .isEmpty();

        String otherUser = UUID.randomUUID().toString();
        try (Connection conn = owner(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    INSERT INTO users (id, tenant_id, email, full_name, role, status)
                    VALUES ('%s', '%s', 'o-%s@example.test', 'Other O', 'owner', 'active')
                    """.formatted(otherUser, otherTenant, tag));
        }
        String otherToken = jwtService.issueAccessToken(
                otherTenant, UUID.fromString(otherUser), "owner").value();
        HttpTestClient.Response theirs = http().get("/sync/changes", otherToken);
        assertThat(theirs.status()).isEqualTo(200);
        assertThat(idsOf(theirs.json(), "products")).contains(otherProduct.toString());
    }

    @Test
    @DisplayName("an unreadable token is 400 with a recovery instruction, not 500")
    void malformedTokenIsABadRequest() {
        HttpTestClient.Response response = http().get("/sync/changes?since=not-a-token", token());

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.headers().first("Content-Type"))
                .startsWith("application/problem+json");
        assertThat(response.json().get("detail").asString()).contains("Omit it");
    }

    @Test
    @DisplayName("the token is opaque and is not a timestamp")
    void theTokenIsNotATimestamp() {
        String syncToken = tokenOf(sync(null));

        assertThat(syncToken).doesNotContain(":").doesNotContain("-");
        assertThat(syncToken)
                .as("a bare number would invite arithmetic on it")
                .isNotEqualTo(syncToken.replaceAll("[^0-9]", ""));
    }
}
