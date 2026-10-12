package com.example.inventory.sales;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
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

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Sales listing by business day")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SalesBusinessDayHttpTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    private static UUID joburg;
    private static UUID joburgOwner;
    private static UUID london;
    private static UUID londonOwner;
    private static UUID utc;
    private static UUID utcOwner;
    private static final java.util.Map<String, UUID> sales = new java.util.HashMap<>();
    private static boolean seeded;

    @BeforeEach
    void seed() throws SQLException {
        if (seeded) {
            return;
        }
        joburg = newTenantId();
        joburgOwner = UUID.randomUUID();
        london = newTenantId();
        londonOwner = UUID.randomUUID();
        utc = newTenantId();
        utcOwner = UUID.randomUUID();
        UUID joburgProduct = UUID.randomUUID();
        UUID londonProduct = UUID.randomUUID();
        UUID utcProduct = UUID.randomUUID();

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            seedTenant(stmt, joburg, joburgOwner, joburgProduct, "bdj", "'Africa/Johannesburg'");
            seedTenant(stmt, london, londonOwner, londonProduct, "bdl", "'Europe/London'");
            seedTenant(stmt, utc, utcOwner, utcProduct, "bdu", null);
        }
        seeded = true;

        stock(joburg, joburgOwner, joburgProduct);
        sales.put("j-10th-23:59", sell(joburg, joburgOwner, joburgProduct, "2026-10-10T23:59:00+02:00"));
        sales.put("j-11th-00:00", sell(joburg, joburgOwner, joburgProduct, "2026-10-11T00:00:00+02:00"));
        sales.put("j-11th-00:30", sell(joburg, joburgOwner, joburgProduct, "2026-10-11T00:30:00+02:00"));
        sales.put("j-11th-23:30", sell(joburg, joburgOwner, joburgProduct, "2026-10-11T23:30:00+02:00"));
        sales.put("j-12th-00:00", sell(joburg, joburgOwner, joburgProduct, "2026-10-12T00:00:00+02:00"));

        stock(london, londonOwner, londonProduct);
        sales.put("l-29th-00:30gmt", sell(london, londonOwner, londonProduct, "2026-03-29T00:30:00Z"));
        sales.put("l-29th-23:30bst", sell(london, londonOwner, londonProduct, "2026-03-29T22:30:00Z"));
        sales.put("l-30th-00:30bst", sell(london, londonOwner, londonProduct, "2026-03-29T23:30:00Z"));

        stock(utc, utcOwner, utcProduct);
        sales.put("u-11th-23:30", sell(utc, utcOwner, utcProduct, "2026-10-11T23:30:00Z"));
        sales.put("u-12th-00:30", sell(utc, utcOwner, utcProduct, "2026-10-12T00:30:00Z"));
    }

    private static void seedTenant(Statement stmt, UUID tenantId, UUID ownerId, UUID productId,
                                   String tag, String timezoneSql) throws SQLException {
        UUID locationId = UUID.randomUUID();
        if (timezoneSql == null) {
            stmt.execute("INSERT INTO tenants (id, slug, name) VALUES ('%s', '%s', 'Shop')"
                    .formatted(tenantId, tag));
        } else {
            stmt.execute("INSERT INTO tenants (id, slug, name, timezone) VALUES ('%s', '%s', 'Shop', %s)"
                    .formatted(tenantId, tag, timezoneSql));
        }
        stmt.execute("""
                INSERT INTO users (id, tenant_id, email, full_name, role, status)
                VALUES ('%s', '%s', 'o-%s@example.test', 'Olu Owner', 'owner', 'active')
                """.formatted(ownerId, tenantId, tag));
        stmt.execute("INSERT INTO locations (id, tenant_id, name, is_default) "
                + "VALUES ('%s', '%s', 'Main', true)".formatted(locationId, tenantId));
        stmt.execute("""
                INSERT INTO products (id, tenant_id, sku, name, selling_price)
                VALUES ('%s', '%s', 'SKU-A', 'Bread', 12.50)
                """.formatted(productId, tenantId));
    }

    private HttpTestClient http() {
        return new HttpTestClient(port);
    }

    private String token(UUID tenantId, UUID userId) {
        return jwtService.issueAccessToken(tenantId, userId, "owner").value();
    }

    private void stock(UUID tenantId, UUID userId, UUID productId) {
        var r = http().postWithToken("/inventory/adjustments", """
                {"productId":"%s","quantityDelta":20,"reason":"opening"}
                """.formatted(productId), token(tenantId, userId));
        assertThat(r.status()).as("fixture stocking must succeed: %s", r.body()).isEqualTo(201);
    }

    private UUID sell(UUID tenantId, UUID userId, UUID productId, String soldAt) {
        var r = http().postWithToken("/sales", """
                {"lines":[{"productId":"%s","quantity":1}],"soldAt":"%s"}
                """.formatted(productId, soldAt), token(tenantId, userId));
        assertThat(r.status()).as("fixture sale must succeed: %s", r.body()).isEqualTo(201);
        return UUID.fromString(r.json().get("id").asString());
    }

    private Set<UUID> listed(UUID tenantId, UUID userId, String from, String to) {
        var r = http().get("/sales?limit=200&from=%s&to=%s".formatted(from, to), token(tenantId, userId));
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        Set<UUID> ids = new HashSet<>();
        r.json().get("items").forEach(item -> ids.add(UUID.fromString(item.get("id").asString())));
        return ids;
    }

    private Set<UUID> ids(String... keys) {
        Set<UUID> out = new HashSet<>();
        for (String k : keys) {
            out.add(sales.get(k));
        }
        return out;
    }

    @Test
    @DisplayName("Johannesburg: a day runs from local midnight to local midnight, not 02:00 to 02:00")
    void johannesburgDayIsLocal() {
        assertThat(listed(joburg, joburgOwner, "2026-10-11", "2026-10-11"))
                .as("00:00 and 00:30 local belong to the 11th; 00:00 on the 12th does not")
                .isEqualTo(ids("j-11th-00:00", "j-11th-00:30", "j-11th-23:30"));
        assertThat(listed(joburg, joburgOwner, "2026-10-10", "2026-10-10"))
                .as("23:59 local on the 10th stays on the 10th, though it is 21:59 UTC")
                .isEqualTo(ids("j-10th-23:59"));
        assertThat(listed(joburg, joburgOwner, "2026-10-12", "2026-10-12"))
                .isEqualTo(ids("j-12th-00:00"));
    }

    @Test
    @DisplayName("a multi-day range includes both end days in full")
    void rangeIsInclusiveOfBothEnds() {
        assertThat(listed(joburg, joburgOwner, "2026-10-10", "2026-10-11"))
                .isEqualTo(ids("j-10th-23:59", "j-11th-00:00", "j-11th-00:30", "j-11th-23:30"));
    }

    @Test
    @DisplayName("London on the day clocks go forward: a 23-hour business day")
    void londonSpringForward() {
        assertThat(listed(london, londonOwner, "2026-03-29", "2026-03-29"))
                .as("22:30 UTC is 23:30 BST on the 29th; 23:30 UTC is already the 30th")
                .isEqualTo(ids("l-29th-00:30gmt", "l-29th-23:30bst"));
        assertThat(listed(london, londonOwner, "2026-03-30", "2026-03-30"))
                .isEqualTo(ids("l-30th-00:30bst"));
    }

    @Test
    @DisplayName("a tenant with the default UTC zone keeps UTC days")
    void utcTenantUnchanged() {
        assertThat(listed(utc, utcOwner, "2026-10-11", "2026-10-11")).isEqualTo(ids("u-11th-23:30"));
        assertThat(listed(utc, utcOwner, "2026-10-12", "2026-10-12")).isEqualTo(ids("u-12th-00:30"));
    }

    @Test
    @DisplayName("registration refuses a zone Postgres could not resolve, and keeps a valid IANA one")
    void registrationValidatesTimezone() {
        var bad = http().post("/auth/register-tenant", registration("bd-bad", "Mars/Olympus_Mons"));
        assertThat(bad.status()).as(bad.body()).isEqualTo(422);

        var offset = http().post("/auth/register-tenant", registration("bd-offset", "+02:00"));
        assertThat(offset.status()).as("a raw offset ignores DST: %s", offset.body()).isEqualTo(422);

        var good = http().post("/auth/register-tenant", registration("bd-good", "Africa/Johannesburg"));
        assertThat(good.status()).as(good.body()).isEqualTo(201);
        String access = good.json().get("accessToken").asString();
        var me = http().get("/me", access);
        assertThat(me.json().get("timezone").asString()).isEqualTo("Africa/Johannesburg");
    }

    private static String registration(String slug, String timezone) {
        return """
                {"businessName":"Zone Shop","slug":"%s","ownerEmail":"%s@example.test",
                 "ownerPassword":"correct-horse-battery","ownerName":"Zola","timezone":"%s"}
                """.formatted(slug, slug, timezone);
    }
}
