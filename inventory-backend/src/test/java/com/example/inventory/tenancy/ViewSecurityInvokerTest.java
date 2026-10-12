package com.example.inventory.tenancy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.inventory.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Views run as the invoker, not the owner")
class ViewSecurityInvokerTest extends AbstractIntegrationTest {

    private static UUID tenantA;
    private static UUID tenantB;
    private static UUID locationA;
    private static UUID locationB;
    private static UUID productA;
    private static UUID productB;
    private static boolean seeded;

    @BeforeEach
    void seed() throws SQLException {
        if (seeded) {
            return;
        }
        tenantA = newTenantId();
        tenantB = newTenantId();
        locationA = UUID.randomUUID();
        locationB = UUID.randomUUID();
        productA = UUID.randomUUID();
        productB = UUID.randomUUID();

        try (Connection conn = owner(); Statement stmt = conn.createStatement()) {
            seedTenant(stmt, tenantA, locationA, productA, "va");
            seedTenant(stmt, tenantB, locationB, productB, "vb");
        }
        seeded = true;
    }

    private static void seedTenant(Statement stmt, UUID tenantId, UUID locationId,
                                   UUID productId, String prefix) throws SQLException {
        String tag = tenantId.toString().substring(0, 8);
        stmt.execute("INSERT INTO tenants (id, slug, name) VALUES ('%s', '%s-%s', 'View Shop')"
                .formatted(tenantId, prefix, tag));
        stmt.execute("INSERT INTO locations (id, tenant_id, name, is_default) "
                + "VALUES ('%s', '%s', 'Main', true)".formatted(locationId, tenantId));
        stmt.execute("""
                INSERT INTO products (id, tenant_id, sku, name, cost_price, selling_price)
                VALUES ('%s', '%s', 'VIEW-%s', 'Viewed Product', 3.00, 9.00)
                """.formatted(productId, tenantId, tag));
        stmt.execute("""
                INSERT INTO product_stock (tenant_id, product_id, location_id, quantity_on_hand)
                VALUES ('%s', '%s', '%s', 12)
                """.formatted(tenantId, productId, locationId));
    }

    private static Connection owner() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Connection app() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_ROLE, APP_PASSWORD);
    }

    @Test
    @DisplayName("v_stock_status shows the bound tenant its own rows, and none of the other's")
    void theStockStatusViewIsScopedToTheBoundTenant() throws SQLException {
        try (Connection conn = app(); Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT set_config('app.tenant_id', '%s', false)".formatted(tenantA));

            assertThat(countInView(stmt, tenantA))
                    .as("tenant A must see its own stock in the view, or the "
                            + "assertions below prove nothing")
                    .isEqualTo(1);

            assertThat(countInView(stmt, tenantB))
                    .as("tenant B's stock must be invisible. A non-zero here means "
                            + "the view is running as its owner — a superuser — and "
                            + "the tenant policy is not being consulted at all.")
                    .isZero();

            assertThat(countForeignRows(stmt, tenantA))
                    .as("and nothing belonging to ANY other tenant, which is broader "
                            + "than the B case and catches a policy that got B right "
                            + "by accident")
                    .isZero();
        }
    }

    @Test
    @DisplayName("with no tenant bound the view is empty, not everything")
    void anUnboundConnectionSeesNothing() throws SQLException {
        try (Connection conn = app(); Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM v_stock_status")) {
                rs.next();
                assertThat(rs.getLong(1))
                        .as("an unbound connection reading the whole catalogue is the "
                                + "most dangerous shape this failure takes")
                        .isZero();
            }
        }
    }

    @Test
    @DisplayName("EVERY view in the schema declares security_invoker = true")
    void everyViewInTheSchemaRunsAsTheInvoker() throws SQLException {
        List<String> offenders = new ArrayList<>();
        int views = 0;

        try (Connection conn = owner();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                     SELECT c.relname,
                            COALESCE(array_to_string(c.reloptions, ','), '') AS options
                     FROM pg_class c
                     JOIN pg_namespace n ON n.oid = c.relnamespace
                     WHERE n.nspname = 'public' AND c.relkind IN ('v', 'm')
                     ORDER BY c.relname
                     """)) {
            while (rs.next()) {
                views++;
                if (!rs.getString("options").contains("security_invoker=true")) {
                    offenders.add(rs.getString("relname"));
                }
            }
        }

        assertThat(views)
                .as("no views found — this test would pass vacuously, so something "
                        + "has gone wrong with the migration rather than with the views")
                .isGreaterThan(0);

        assertThat(offenders)
                .as("these views execute with their OWNER's privileges and RLS "
                        + "context. The owner is inventory_owner, a superuser, which "
                        + "bypasses row-level security entirely — so each of these "
                        + "returns every tenant's rows to any caller. Add "
                        + "`WITH (security_invoker = true)` in R__views.sql.")
                .isEmpty();
    }

    private static long countInView(Statement stmt, UUID tenantId) throws SQLException {
        return countWhere(stmt, "tenant_id = '%s'".formatted(tenantId));
    }

    private static long countForeignRows(Statement stmt, UUID tenantId) throws SQLException {
        return countWhere(stmt, "tenant_id <> '%s'".formatted(tenantId));
    }

    private static long countWhere(Statement stmt, String predicate) throws SQLException {
        try (ResultSet rs = stmt.executeQuery(
                "SELECT count(*) FROM v_stock_status WHERE " + predicate)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
