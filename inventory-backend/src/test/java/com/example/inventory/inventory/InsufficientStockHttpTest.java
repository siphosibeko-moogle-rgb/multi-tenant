package com.example.inventory.inventory;

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
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.inventory.AbstractIntegrationTest;
import com.example.inventory.auth.HttpTestClient;
import com.example.inventory.auth.JwtService;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Insufficient stock over HTTP")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InsufficientStockHttpTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    private static UUID tenantId;
    private static UUID userId;
    private static UUID locationId;
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
        productId = UUID.randomUUID();

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {

            stmt.execute("INSERT INTO tenants (id, slug, name) VALUES ('%s', 'http409-%s', 'Shop')"
                    .formatted(tenantId, tenantId.toString().substring(0, 8)));
            stmt.execute("""
                    INSERT INTO users (id, tenant_id, email, full_name, role, status)
                    VALUES ('%s', '%s', 'mgr-%s@example.test', 'Mo Manager', 'manager', 'active')
                    """.formatted(userId, tenantId, tenantId.toString().substring(0, 8)));
            stmt.execute("INSERT INTO locations (id, tenant_id, name, is_default) "
                    + "VALUES ('%s', '%s', 'Main', true)".formatted(locationId, tenantId));
            stmt.execute("""
                    INSERT INTO products (id, tenant_id, sku, name, allow_negative_stock)
                    VALUES ('%s', '%s', 'SKU-EMPTY-HTTP', 'Nothing In Stock', false)
                    """.formatted(productId, tenantId));
        }
        seeded = true;
    }

    private HttpTestClient http() {
        return new HttpTestClient(port);
    }

    private String managerToken() {
        return jwtService.issueAccessToken(tenantId, userId, "manager").value();
    }

    private long movementCount() {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            return new JdbcTemplate(new org.springframework.jdbc.datasource
                    .SingleConnectionDataSource(conn, true))
                    .queryForObject("SELECT count(*) FROM stock_movements WHERE product_id = ?",
                            Long.class, productId);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("409 with productId, requested and available in the problem body")
    void oversellReturnsAProblemDetailWithTheNumbers() {
        var stocked = http().postWithToken("/inventory/adjustments", """
                {"productId":"%s","quantityDelta":3,"reason":"opening"}
                """.formatted(productId), managerToken());
        assertThat(stocked.status()).as("precondition: three units on hand").isEqualTo(201);

        long movementsBefore = movementCount();

        var response = http().postWithToken("/inventory/adjustments", """
                {"productId":"%s","quantityDelta":-5,"reason":"damaged in transit"}
                """.formatted(productId), managerToken());

        assertThat(response.status())
                .as("the contract's InsufficientStock response is a 409")
                .isEqualTo(409);

        assertThat(response.headers().first("Content-Type"))
                .as("RFC 9457 — problem+json, not application/json. A client that branches on "
                        + "content type would not recognise the latter as an error shape.")
                .contains("application/problem+json");

        var body = response.json();

        for (String field : java.util.List.of("productId", "requested", "available")) {
            assertThat(body.at("/" + field).isMissingNode())
                    .as("%s must be PRESENT in the problem body — the contract's "
                            + "InsufficientStock response names it, and a client cannot ask the "
                            + "user to reorder without it", field)
                    .isFalse();
        }

        assertThat(body.at("/productId").asString())
                .as("productId must name the product that could not be moved — with several "
                        + "lines in flight, a client cannot infer which one failed")
                .isEqualTo(productId.toString());

        assertThat(body.at("/requested").asDouble())
                .as("requested is the absolute quantity asked for, not the signed delta: the "
                        + "caller sent -5 and the shortfall is 5")
                .isEqualTo(5.0);

        assertThat(body.at("/available").asDouble())
                .as("available is what there actually was — 3, not 0 and not 5. Distinct from "
                        + "requested so the two cannot be swapped without this failing.")
                .isEqualTo(3.0);

        assertThat(body.at("/status").asInt()).isEqualTo(409);
        assertThat(body.at("/title").asString()).isNotBlank();
        assertThat(body.at("/type").asString()).contains("insufficient-stock");

        assertThat(response.body())
                .as("never a stack trace")
                .doesNotContain("Exception").doesNotContain("at com.example");

        assertThat(movementCount())
                .as("and stock is unchanged afterwards — the refused adjustment must leave no "
                        + "row behind, which is the second half of the milestone criterion")
                .isEqualTo(movementsBefore);
    }

    @Test
    @DisplayName("a successful adjustment is 201, so the 409 is not the only outcome")
    void aValidAdjustmentSucceeds() {
        var response = http().postWithToken("/inventory/adjustments", """
                {"productId":"%s","quantityDelta":7,"reason":"found in the back"}
                """.formatted(productId), managerToken());

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.at("/movementType")).isEqualTo("adjustment");
        assertThat(response.json().at("/balanceAfter").isMissingNode())
                .as("a successful adjustment reports the resulting balance")
                .isFalse();
    }
}
