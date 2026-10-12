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

import com.example.inventory.AbstractIntegrationTest;
import com.example.inventory.auth.HttpTestClient;
import com.example.inventory.auth.JwtService;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Stock state")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StockStateTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    private static UUID tenantId;
    private static UUID userId;
    private static UUID locationId;
    private static UUID noThreshold;
    private static UUID belowThreshold;
    private static UUID empty;
    private static boolean seeded;

    @BeforeEach
    void seed() throws SQLException {
        if (seeded) {
            return;
        }
        tenantId = newTenantId();
        userId = UUID.randomUUID();
        locationId = UUID.randomUUID();
        noThreshold = UUID.randomUUID();
        belowThreshold = UUID.randomUUID();
        empty = UUID.randomUUID();
        String tag = tenantId.toString().substring(0, 8);

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {

            stmt.execute("INSERT INTO tenants (id, slug, name) VALUES ('%s', 'state-%s', 'Shop')"
                    .formatted(tenantId, tag));
            stmt.execute("""
                    INSERT INTO users (id, tenant_id, email, full_name, role, status)
                    VALUES ('%s', '%s', 'own-%s@example.test', 'Olu Owner', 'owner', 'active')
                    """.formatted(userId, tenantId, tag));
            stmt.execute("INSERT INTO locations (id, tenant_id, name, is_default) "
                    + "VALUES ('%s', '%s', 'Main', true)".formatted(locationId, tenantId));

            stmt.execute("""
                    INSERT INTO products (id, tenant_id, sku, name, reorder_point)
                    VALUES ('%s', '%s', 'SKU-NO-RP', 'No Threshold', NULL)
                    """.formatted(noThreshold, tenantId));
            stmt.execute("""
                    INSERT INTO products (id, tenant_id, sku, name, reorder_point)
                    VALUES ('%s', '%s', 'SKU-LOW', 'Below Threshold', 25)
                    """.formatted(belowThreshold, tenantId));
            stmt.execute("""
                    INSERT INTO products (id, tenant_id, sku, name, reorder_point)
                    VALUES ('%s', '%s', 'SKU-EMPTY', 'Nothing Left', NULL)
                    """.formatted(empty, tenantId));
        }
        seeded = true;
    }

    private HttpTestClient http() {
        return new HttpTestClient(port);
    }

    private String token() {
        return jwtService.issueAccessToken(tenantId, userId, "owner").value();
    }

    private void stock(UUID productId, int quantity) {
        var response = http().postWithToken("/inventory/adjustments", """
                {"productId":"%s","quantityDelta":%d,"reason":"opening"}
                """.formatted(productId, quantity), token());
        assertThat(response.status()).as("fixture must post successfully").isEqualTo(201);
    }

    private String fromTheView(UUID productId) {
        var response = http().get("/inventory?limit=200", token());
        assertThat(response.status()).isEqualTo(200);
        for (var item : response.json().get("items")) {
            if (productId.toString().equals(item.get("productId").asString())) {
                return item.get("stockState").asString();
            }
        }
        throw new AssertionError("product " + productId + " absent from /inventory");
    }

    private String fromJava(UUID productId) {
        var response = http().get("/products/" + productId, token());
        assertThat(response.status()).isEqualTo(200);
        return response.json().get("stockState").asString();
    }

    private void bothAgree(UUID productId, String expected) {
        String view = fromTheView(productId);
        String java = fromJava(productId);

        assertThat(view)
                .as("v_stock_status must answer %s", expected).isEqualTo(expected);
        assertThat(java)
                .as("ProductCatalog.stockState must answer %s", expected).isEqualTo(expected);
        assertThat(java)
                .as("the view and its Java mirror must not disagree about one product")
                .isEqualTo(view);
    }

    @Test
    @DisplayName("stock with no reorder point is ok, not unknown")
    void noReorderPointIsOk() {
        stock(noThreshold, 10);

        bothAgree(noThreshold, "ok");
    }

    @Test
    @DisplayName("stock at or below the reorder point is reorder")
    void belowTheThresholdIsReorder() {
        stock(belowThreshold, 10);

        bothAgree(belowThreshold, "reorder");
    }

    @Test
    @DisplayName("no stock is out_of_stock, threshold or not")
    void nothingOnHandIsOutOfStock() {
        assertThat(fromJava(empty)).isEqualTo("out_of_stock");
    }

    @Test
    @DisplayName("out_of_stock outranks a reorder point that would also match")
    void emptyIsNotReportedAsReorder() {
        stock(belowThreshold, 0 - 10);

        bothAgree(belowThreshold, "out_of_stock");
    }
}
