package com.example.inventory.sales;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
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

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Overselling a product with no stock")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ZeroStockOversellHttpTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    private static UUID tenantId;
    private static UUID userId;
    private static UUID locationId;
    private static UUID neverStocked;
    private static UUID stockedThenEmptied;
    private static boolean seeded;

    @BeforeEach
    void seed() throws SQLException {
        if (seeded) {
            return;
        }
        tenantId = newTenantId();
        userId = UUID.randomUUID();
        locationId = UUID.randomUUID();
        neverStocked = UUID.randomUUID();
        stockedThenEmptied = UUID.randomUUID();
        String tag = tenantId.toString().substring(0, 8);

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO tenants (id, slug, name) VALUES ('%s', 'probe-%s', 'Shop')"
                    .formatted(tenantId, tag));
            stmt.execute("""
                    INSERT INTO users (id, tenant_id, email, full_name, role, status)
                    VALUES ('%s', '%s', 'o-%s@example.test', 'Olu Owner', 'owner', 'active')
                    """.formatted(userId, tenantId, tag));
            stmt.execute("INSERT INTO locations (id, tenant_id, name, is_default) "
                    + "VALUES ('%s', '%s', 'Main', true)".formatted(locationId, tenantId));
            stmt.execute("""
                    INSERT INTO products (id, tenant_id, sku, name, selling_price)
                    VALUES ('%s', '%s', 'SKU-NEVER', 'Never Stocked', 5.00)
                    """.formatted(neverStocked, tenantId));
            stmt.execute("""
                    INSERT INTO products (id, tenant_id, sku, name, selling_price)
                    VALUES ('%s', '%s', 'SKU-EMPTIED', 'Emptied', 5.00)
                    """.formatted(stockedThenEmptied, tenantId));
        }
        seeded = true;
    }

    private HttpTestClient http() {
        return new HttpTestClient(port);
    }

    private String token() {
        return jwtService.issueAccessToken(tenantId, userId, "owner").value();
    }

    @Test
    @DisplayName("a product that was never stocked reports available 0, not null")
    void neverStockedReportsZero() {
        var response = http().postWithToken("/sales", """
                {"lines":[{"productId":"%s","quantity":6}]}
                """.formatted(neverStocked), token());

        assertThat(response.status()).isEqualTo(409);

        var body = response.json();

        assertThat(body.has("requested")).as("requested must be present").isTrue();
        assertThat(body.get("requested").isNull()).as("requested must not be null").isFalse();
        assertThat(body.has("available")).as("available must be present").isTrue();
        assertThat(body.get("available").isNull())
                .as("available must not be null — a null here is unconstructable by a "
                        + "generated client and costs the caller `requested` as well")
                .isFalse();

        assertThat(body.get("requested").asInt()).isEqualTo(6);
        assertThat(body.get("available").asDouble()).isZero();
        assertThat(body.get("productId").asString()).isEqualTo(neverStocked.toString());
    }

    @Test
    @DisplayName("a product stocked and then emptied reports available 0 too")
    void emptiedReportsZero() {
        http().postWithToken("/inventory/adjustments", """
                {"productId":"%s","quantityDelta":2,"reason":"opening"}
                """.formatted(stockedThenEmptied), token());
        var sold = http().postWithToken("/sales", """
                {"lines":[{"productId":"%s","quantity":2}]}
                """.formatted(stockedThenEmptied), token());
        assertThat(sold.status()).as("the fixture sale must succeed").isEqualTo(201);

        var response = http().postWithToken("/sales", """
                {"lines":[{"productId":"%s","quantity":6}]}
                """.formatted(stockedThenEmptied), token());

        assertThat(response.status()).isEqualTo(409);
        var body = response.json();

        assertThat(body.has("available")).isTrue();
        assertThat(body.get("available").isNull()).isFalse();
        assertThat(body.get("available").asDouble()).isZero();
        assertThat(body.get("requested").asInt()).isEqualTo(6);
    }
}
