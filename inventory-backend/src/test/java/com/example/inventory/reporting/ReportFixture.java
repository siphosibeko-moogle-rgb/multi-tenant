package com.example.inventory.reporting;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import com.example.inventory.auth.HttpTestClient;

final class ReportFixture {

    static final String A_COST = "7.00";
    static final String A_PRICE = "20.00";
    static final int A_OPENING = 100;

    static final String B_COST = "3.00";
    static final String B_PRICE = "5.00";
    static final int B_OPENING = 100;

    static final String C_COST = "1.00";
    static final String C_PRICE = "2.00";
    static final int C_OPENING = 6;

    static final String D_COST = "2.00";
    static final String D_PRICE = "4.00";
    static final int D_OPENING = 10;
    static final int D_REORDER_POINT = 50;

    final UUID tenantId;
    final UUID userId;
    final UUID locationId;
    final UUID productA;
    final UUID productB;
    final UUID productC;
    final UUID productD;

    private ReportFixture(UUID tenantId, UUID userId, UUID locationId,
                          UUID a, UUID b, UUID c, UUID d) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.locationId = locationId;
        this.productA = a;
        this.productB = b;
        this.productC = c;
        this.productD = d;
    }

    static ReportFixture create(String jdbcUrl, String owner, String password,
                                HttpTestClient http,
                                java.util.function.BiFunction<UUID, UUID, String> tokenFor)
            throws SQLException {

        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        String tag = tenantId.toString().substring(0, 8);

        try (Connection conn = DriverManager.getConnection(jdbcUrl, owner, password);
             Statement stmt = conn.createStatement()) {

            stmt.execute("INSERT INTO tenants (id, slug, name) VALUES ('%s', 'rep-%s', 'Report Shop')"
                    .formatted(tenantId, tag));
            stmt.execute("""
                    INSERT INTO users (id, tenant_id, email, full_name, role, status)
                    VALUES ('%s', '%s', 'owner-%s@example.test', 'Ada Owner', 'owner', 'active')
                    """.formatted(userId, tenantId, tag));
            stmt.execute("INSERT INTO locations (id, tenant_id, name, is_default) "
                    + "VALUES ('%s', '%s', 'Main', true)".formatted(locationId, tenantId));

            product(stmt, a, tenantId, "REP-A-" + tag, "Almond Tart", A_COST, A_PRICE, null);
            product(stmt, b, tenantId, "REP-B-" + tag, "Bran Roll", B_COST, B_PRICE, null);
            product(stmt, c, tenantId, "REP-C-" + tag, "Cinnamon Bun", C_COST, C_PRICE, null);
            product(stmt, d, tenantId, "REP-D-" + tag, "Date Loaf", D_COST, D_PRICE,
                    String.valueOf(D_REORDER_POINT));
        }

        String token = tokenFor.apply(tenantId, userId);

        adjust(http, token, a, A_OPENING);
        adjust(http, token, b, B_OPENING);
        adjust(http, token, c, C_OPENING);
        adjust(http, token, d, D_OPENING);

        sell(http, token, a, 4);
        sell(http, token, b, 9);
        String saleThree = saleIdOf(sell(http, token, a, 2));
        http.postWithToken("/sales/" + saleThree + "/returns", """
                {"lines":[{"productId":"%s","quantity":1}],"reason":"changed mind","restock":true}
                """.formatted(a), token);
        sell(http, token, c, 6);

        return new ReportFixture(tenantId, userId, locationId, a, b, c, d);
    }

    private static void product(Statement stmt, UUID id, UUID tenantId, String sku, String name,
                                String cost, String price, String reorderPoint) throws SQLException {
        stmt.execute("""
                INSERT INTO products (id, tenant_id, sku, name, cost_price, selling_price,
                                      tax_rate, reorder_point)
                VALUES ('%s', '%s', '%s', '%s', %s, %s, 0.10, %s)
                """.formatted(id, tenantId, sku, name, cost, price,
                reorderPoint == null ? "NULL" : reorderPoint));
    }

    private static void adjust(HttpTestClient http, String token, UUID productId, int quantity) {
        HttpTestClient.Response response = http.postWithToken("/inventory/adjustments", """
                {"productId":"%s","quantityDelta":%d,"reason":"opening"}
                """.formatted(productId, quantity), token);
        require(response, 201, "opening stock for " + productId);
    }

    private static HttpTestClient.Response sell(HttpTestClient http, String token,
                                                UUID productId, int quantity) {
        HttpTestClient.Response response = http.postWithToken("/sales", """
                {"lines":[{"productId":"%s","quantity":%d}]}
                """.formatted(productId, quantity), token);
        require(response, 201, "sale of %d x %s".formatted(quantity, productId));
        return response;
    }

    private static String saleIdOf(HttpTestClient.Response response) {
        return response.json().get("id").asString();
    }

    private static void require(HttpTestClient.Response response, int status, String what) {
        if (response.status() != status) {
            throw new IllegalStateException(
                    "fixture step failed (" + what + "): HTTP " + response.status()
                            + " " + response.body());
        }
    }

    static final String EXPECTED_REVENUE = "157.00";

    static final String EXPECTED_COST = "68.00";

    static final String EXPECTED_GROSS_PROFIT = "89.00";

    static final long EXPECTED_SALES_COUNT = 4;

    static final String EXPECTED_A_ON_HAND = "95";
    static final String EXPECTED_B_ON_HAND = "91";
    static final String EXPECTED_D_ON_HAND = "10";

    static final String EXPECTED_INVENTORY_COST_VALUE = "958.00";

    static final String EXPECTED_INVENTORY_RETAIL_VALUE = "2395.00";

    static final long EXPECTED_VALUED_PRODUCT_COUNT = 3;

    static final String EXPECTED_A_UNITS = "5.000";
    static final String EXPECTED_B_UNITS = "9.000";
    static final String EXPECTED_C_UNITS = "6.000";

    static final String EXPECTED_A_REVENUE = "100.00";
    static final String EXPECTED_B_REVENUE = "45.00";
}
