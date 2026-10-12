package com.example.inventory.reporting;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

@DisplayName("The report endpoints agree with each other")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReportAgreementHttpTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    private static UUID tenantId;
    private static UUID userId;
    private static UUID slow;
    private static UUID fast;
    private static boolean seeded;

    @BeforeEach
    void seedOnce() throws SQLException {
        if (seeded) {
            return;
        }
        tenantId = UUID.randomUUID();
        userId = UUID.randomUUID();
        slow = UUID.randomUUID();
        fast = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        String tag = tenantId.toString().substring(0, 8);

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO tenants (id, slug, name) VALUES ('%s', 'agr-%s', 'Agree Ltd')"
                    .formatted(tenantId, tag));
            stmt.execute("""
                    INSERT INTO users (id, tenant_id, email, full_name, role, status)
                    VALUES ('%s', '%s', 'owner-%s@example.test', 'Abe Owner', 'owner', 'active')
                    """.formatted(userId, tenantId, tag));
            stmt.execute("INSERT INTO locations (id, tenant_id, name, is_default) "
                    + "VALUES ('%s', '%s', 'Main', true)".formatted(locationId, tenantId));
            stmt.execute("""
                    INSERT INTO products (id, tenant_id, sku, name, cost_price, selling_price,
                                          tax_rate)
                    VALUES ('%s', '%s', 'AGR-SLOW-%s', 'Slow Expensive', 11.00, 30.00, 0.10)
                    """.formatted(slow, tenantId, tag));
            stmt.execute("""
                    INSERT INTO products (id, tenant_id, sku, name, cost_price, selling_price,
                                          tax_rate)
                    VALUES ('%s', '%s', 'AGR-FAST-%s', 'Fast Cheap', 1.00, 2.00, 0.10)
                    """.formatted(fast, tenantId, tag));
        }

        String token = owner();
        adjust(token, slow, 500);
        adjust(token, fast, 500);

        LocalDate today = today();
        sellOn(token, slow, 2, today);
        sellOn(token, fast, 7, today);
        sellOn(token, slow, 1, today.minusDays(1));
        sellOn(token, fast, 4, today.minusDays(3));
        sellOn(token, slow, 3, today.minusDays(6));
        sellOn(token, fast, 9, today.minusDays(9));
        sellOn(token, slow, 5, today.minusDays(12));

        seeded = true;
    }

    private void adjust(String token, UUID productId, int quantity) {
        require(http().postWithToken("/inventory/adjustments", """
                {"productId":"%s","quantityDelta":%d,"reason":"opening"}
                """.formatted(productId, quantity), token), 201);
    }

    private void sellOn(String token, UUID productId, int quantity, LocalDate day) {
        require(http().postWithToken("/sales", """
                {"lines":[{"productId":"%s","quantity":%d}],"soldAt":"%sT12:00:00Z"}
                """.formatted(productId, quantity, day), token), 201);
    }

    private static void require(HttpTestClient.Response response, int expected) {
        if (response.status() != expected) {
            throw new IllegalStateException(
                    "fixture step failed: HTTP " + response.status() + " " + response.body());
        }
    }

    private HttpTestClient http() {
        return new HttpTestClient(port);
    }

    private String owner() {
        return jwtService.issueAccessToken(tenantId, userId, "owner").value();
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    private JsonNode get(String path) {
        HttpTestClient.Response response = http().get(path, owner());
        assertThat(response.status()).as("%s -> %s", path, response.body()).isEqualTo(200);
        return response.json();
    }

    @Test
    @DisplayName("salesTotal equals the sales summary over the same window")
    void salesTotalMatchesTheSummary() {
        LocalDate today = today();

        for (Map.Entry<String, Integer> period : Map.of(
                "today", 1, "week", 7, "month", 30).entrySet()) {

            JsonNode dashboard = get("/reports/dashboard?period=" + period.getKey());
            JsonNode summary = get("/reports/sales-summary?from=%s&to=%s&groupBy=day"
                    .formatted(today.minusDays(period.getValue() - 1L), today));

            assertThat(dashboard.get("salesTotal").decimalValue())
                    .as("period=%s must be the same window the summary reports",
                            period.getKey())
                    .isEqualByComparingTo(sum(summary, "revenue"));
            assertThat(dashboard.get("grossProfit").decimalValue())
                    .as("period=%s gross profit", period.getKey())
                    .isEqualByComparingTo(sum(summary, "grossProfit"));
        }

        BigDecimal daily = get("/reports/dashboard?period=today").get("salesTotal").decimalValue();
        BigDecimal weekly = get("/reports/dashboard?period=week").get("salesTotal").decimalValue();
        BigDecimal monthly = get("/reports/dashboard?period=month").get("salesTotal").decimalValue();

        assertThat(daily).isGreaterThan(BigDecimal.ZERO);
        assertThat(weekly).isGreaterThan(daily);
        assertThat(monthly).isGreaterThan(weekly);
    }

    private static BigDecimal sum(JsonNode summary, String field) {
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode bucket : summary.get("buckets")) {
            total = total.add(bucket.get(field).decimalValue());
        }
        return total;
    }

    @Test
    @DisplayName("salesTrend equals a day-grouped summary over the same 14 days")
    void trendMatchesADayGroupedSummary() {
        LocalDate today = today();

        JsonNode trend = get("/reports/dashboard").get("salesTrend");
        JsonNode summary = get("/reports/sales-summary?from=%s&to=%s&groupBy=day"
                .formatted(today.minusDays(13), today));

        assertThat(trend.size()).isEqualTo(summary.get("buckets").size()).isEqualTo(14);

        int nonZeroDays = 0;
        for (int i = 0; i < 14; i++) {
            JsonNode point = trend.get(i);
            JsonNode bucket = summary.get("buckets").get(i);

            assertThat(point.get("day").asString())
                    .isEqualTo(bucket.get("periodStart").asString());
            assertThat(point.get("revenue").decimalValue())
                    .as("day %s", point.get("day").asString())
                    .isEqualByComparingTo(bucket.get("revenue").decimalValue());

            if (point.get("revenue").decimalValue().signum() != 0) {
                nonZeroDays++;
            }
        }

        assertThat(nonZeroDays)
                .as("the fixture must put sales on several distinct days, or this "
                        + "comparison is mostly zero equals zero")
                .isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("dashboard topProducts is the same ranking as /reports/top-products")
    void topProductsMatchAcrossBothEndpoints() {
        LocalDate today = today();

        JsonNode dashboard = get("/reports/dashboard?period=week").get("topProducts");
        JsonNode standalone = get("/reports/top-products?order=best&from=%s&to=%s"
                .formatted(today.minusDays(6), today));

        assertThat(dashboard.size()).isGreaterThan(0);

        List<String> dashboardOrder = new ArrayList<>();
        Map<String, BigDecimal> dashboardUnits = new LinkedHashMap<>();
        for (JsonNode row : dashboard) {
            dashboardOrder.add(row.get("productId").asString());
            dashboardUnits.put(row.get("productId").asString(),
                    row.get("unitsSold").decimalValue());
        }

        List<String> standaloneOrder = new ArrayList<>();
        for (JsonNode row : standalone) {
            standaloneOrder.add(row.get("productId").asString());
        }

        assertThat(standaloneOrder.subList(0, dashboardOrder.size()))
                .as("one ranking key, computed in one place — if these ever diverge, "
                        + "one of the two endpoints has started ranking by revenue")
                .isEqualTo(dashboardOrder);

        for (JsonNode row : standalone) {
            String id = row.get("productId").asString();
            if (dashboardUnits.containsKey(id)) {
                assertThat(row.get("unitsSold").decimalValue())
                        .as("unitsSold for %s", id)
                        .isEqualByComparingTo(dashboardUnits.get(id));
            }
        }

        assertThat(standaloneOrder.get(0)).isEqualTo(fast.toString());
        assertThat(standalone.get(0).get("revenue").decimalValue())
                .isLessThan(standalone.get(1).get("revenue").decimalValue());
    }

    @Test
    @DisplayName("dashboard inventoryValue equals the valuation endpoint's totalCostValue")
    void inventoryValueMatchesTheValuationEndpoint() {
        BigDecimal fromDashboard =
                get("/reports/dashboard").get("inventoryValue").decimalValue();
        BigDecimal fromValuation =
                get("/reports/inventory-valuation").get("totalCostValue").decimalValue();

        assertThat(fromDashboard)
                .as("two endpoints, one figure")
                .isEqualByComparingTo(fromValuation);
        assertThat(fromDashboard)
                .as("and not two zeroes agreeing")
                .isGreaterThan(BigDecimal.ZERO);
    }
}
