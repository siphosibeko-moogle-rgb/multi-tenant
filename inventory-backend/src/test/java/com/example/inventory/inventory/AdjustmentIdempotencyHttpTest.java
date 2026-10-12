package com.example.inventory.inventory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.example.inventory.AbstractIntegrationTest;
import com.example.inventory.auth.HttpTestClient;
import com.example.inventory.auth.JwtService;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Adjustment idempotency")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdjustmentIdempotencyHttpTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    private static UUID tenantId;
    private static UUID userId;
    private static UUID productId;
    private static boolean seeded;

    @BeforeEach
    void seed() throws SQLException {
        if (seeded) {
            return;
        }
        tenantId = newTenantId();
        userId = UUID.randomUUID();
        productId = UUID.randomUUID();
        String tag = tenantId.toString().substring(0, 8);

        try (Connection conn = owner(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO tenants (id, slug, name) VALUES ('%s', 'adj-%s', 'Adj Ltd')"
                    .formatted(tenantId, tag));
            stmt.execute("""
                    INSERT INTO users (id, tenant_id, email, full_name, role, status)
                    VALUES ('%s', '%s', 'owner-%s@example.test', 'Ad Owner', 'owner', 'active')
                    """.formatted(userId, tenantId, tag));
            stmt.execute("INSERT INTO locations (id, tenant_id, name, is_default) "
                    + "VALUES ('%s', '%s', 'Main', true)"
                    .formatted(UUID.randomUUID(), tenantId));
            stmt.execute("""
                    INSERT INTO products (id, tenant_id, sku, name, cost_price, selling_price)
                    VALUES ('%s', '%s', 'ADJ-%s', 'Adjusted Item', 2.00, 5.00)
                    """.formatted(productId, tenantId, tag));
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

    private HttpTestClient.Response adjust(int delta, UUID key) {
        return http().postWithHeaders("/inventory/adjustments", """
                {"productId":"%s","quantityDelta":%d,"reason":"stocktake"}
                """.formatted(productId, delta), token(),
                key == null ? java.util.Map.of()
                        : java.util.Map.of("Idempotency-Key", key.toString()));
    }

    private long onHand() throws SQLException {
        try (Connection conn = owner();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                     SELECT COALESCE(SUM(quantity_on_hand), 0) FROM product_stock
                     WHERE tenant_id = '%s' AND product_id = '%s'
                     """.formatted(tenantId, productId))) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long movementCount() throws SQLException {
        try (Connection conn = owner();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                     SELECT count(*) FROM stock_movements
                     WHERE tenant_id = '%s' AND product_id = '%s'
                     """.formatted(tenantId, productId))) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    @DisplayName("replaying the same key returns the original movement and moves stock once")
    void aReplayMovesStockOnce() throws SQLException {
        UUID key = UUID.randomUUID();
        long before = onHand();
        long movementsBefore = movementCount();

        HttpTestClient.Response first = adjust(9, key);
        assertThat(first.status()).as("body: %s", first.body()).isEqualTo(201);

        HttpTestClient.Response replay = adjust(9, key);

        assertThat(replay.status())
                .as("a replay is 200, the same shape POST /sales uses")
                .isEqualTo(200);
        assertThat(replay.json().get("id").asLong())
                .as("the replay must return the ORIGINAL movement, not a new one")
                .isEqualTo(first.json().get("id").asLong());

        assertThat(onHand())
                .as("stock must have moved exactly once for two identical requests")
                .isEqualTo(before + 9);
        assertThat(movementCount())
                .as("and exactly one row may exist in the append-only ledger")
                .isEqualTo(movementsBefore + 1);
    }

    @Test
    @DisplayName("a different key posts a second, separate movement")
    void adifferentKeyIsANewAdjustment() throws SQLException {
        long before = onHand();

        assertThat(adjust(4, UUID.randomUUID()).status()).isEqualTo(201);
        assertThat(adjust(4, UUID.randomUUID()).status()).isEqualTo(201);

        assertThat(onHand()).isEqualTo(before + 8);
    }

    @Test
    @DisplayName("no key at all still posts every time — the old behaviour is unchanged")
    void withoutAKeyNothingIsDeduplicated() throws SQLException {
        long before = onHand();

        assertThat(adjust(3, null).status()).isEqualTo(201);
        assertThat(adjust(3, null).status()).isEqualTo(201);

        assertThat(onHand()).isEqualTo(before + 6);
    }

    @Test
    @DisplayName("two concurrent replays of one key still move stock once")
    void concurrentReplaysMoveStockOnce() throws Exception {
        UUID key = UUID.randomUUID();
        long before = onHand();
        long movementsBefore = movementCount();

        int attempts = 8;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                calls.add(() -> adjust(5, key).status());
            }
            List<Future<Integer>> results = pool.invokeAll(calls);

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
            assertThat(statuses)
                    .as("every attempt must be answered, and only with 200 or 201: %s",
                            statuses)
                    .allMatch(status -> status == 200 || status == 201);
            assertThat(statuses).filteredOn(status -> status == 201)
                    .as("exactly one attempt may be the one that created it: %s", statuses)
                    .hasSize(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(onHand())
                .as("eight concurrent replays of one key must move 5 units, not 40")
                .isEqualTo(before + 5);
        assertThat(movementCount()).isEqualTo(movementsBefore + 1);
    }
}
