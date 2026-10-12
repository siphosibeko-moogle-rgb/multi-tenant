package com.example.inventory.tenancy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import javax.sql.DataSource;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.example.inventory.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Tenant isolation")
class TenantIsolationTest extends AbstractIntegrationTest {

    private static final List<String> TENANT_SCOPED_TABLES = List.of(
            "users", "refresh_tokens", "locations", "categories", "suppliers", "products",
            "product_suppliers", "stock_movements", "product_stock", "stocktakes",
            "stocktake_lines", "sales", "sale_items", "purchase_orders", "purchase_order_items",
            "supplier_lead_time_observations", "demand_daily", "forecasts", "forecast_accuracy",
            "reorder_recommendations", "audit_log",
            "sale_returns",
            "change_log");

    private static final UUID TENANT_A = newTenantId();
    private static final UUID TENANT_B = newTenantId();

    @Autowired
    private DataSource appDataSource;

    private static boolean seeded;

    @BeforeEach
    void seedTwoTenants() throws SQLException {
        if (seeded) {
            return;
        }
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {

            seedTenant(stmt, TENANT_A, "alpha");
            seedTenant(stmt, TENANT_B, "beta");
        }
        seeded = true;
    }

    private static void seedTenant(Statement stmt, UUID tenant, String label) throws SQLException {
        String t = tenant.toString();
        UUID user = UUID.randomUUID();
        UUID location = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        UUID supplier = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID stocktake = UUID.randomUUID();
        UUID sale = UUID.randomUUID();
        UUID purchaseOrder = UUID.randomUUID();

        stmt.execute("INSERT INTO tenants (id, slug, name) VALUES ('%s', 'tn-%s', '%s Trading')"
                .formatted(t, tenant.toString().substring(0, 8), label));

        stmt.execute("""
                INSERT INTO users (id, tenant_id, email, full_name, role, status)
                VALUES ('%s', '%s', '%s@example.test', 'Seed User', 'owner', 'active')
                """.formatted(user, t, label + "-" + user.toString().substring(0, 8)));

        stmt.execute("""
                INSERT INTO refresh_tokens (tenant_id, user_id, token_hash, expires_at)
                VALUES ('%s', '%s', 'hash-%s', now() + interval '1 day')
                """.formatted(t, user, UUID.randomUUID()));

        stmt.execute("INSERT INTO locations (id, tenant_id, name, is_default) "
                + "VALUES ('%s', '%s', 'Main Store', true)".formatted(location, t));

        stmt.execute("INSERT INTO categories (id, tenant_id, name) VALUES ('%s', '%s', 'Dry Goods')"
                .formatted(category, t));

        stmt.execute("INSERT INTO suppliers (id, tenant_id, name) VALUES ('%s', '%s', 'Acme Wholesale')"
                .formatted(supplier, t));

        stmt.execute("""
                INSERT INTO products (id, tenant_id, sku, name, category_id, cost_price, selling_price)
                VALUES ('%s', '%s', 'SKU-001', 'Maize Meal 10kg', '%s', 45.00, 79.99)
                """.formatted(product, t, category));

        stmt.execute("""
                INSERT INTO product_suppliers (tenant_id, product_id, supplier_id, unit_cost, is_preferred)
                VALUES ('%s', '%s', '%s', 45.00, true)
                """.formatted(t, product, supplier));

        stmt.execute("""
                INSERT INTO stock_movements
                    (tenant_id, product_id, location_id, movement_type, quantity_delta, unit_cost)
                VALUES ('%s', '%s', '%s', 'purchase_receipt', 100, 45.00)
                """.formatted(t, product, location));

        stmt.execute("INSERT INTO stocktakes (id, tenant_id, location_id) VALUES ('%s', '%s', '%s')"
                .formatted(stocktake, t, location));

        stmt.execute("""
                INSERT INTO stocktake_lines (tenant_id, stocktake_id, product_id, expected_qty, counted_qty)
                VALUES ('%s', '%s', '%s', 100, 98)
                """.formatted(t, stocktake, product));

        stmt.execute("""
                INSERT INTO sales (id, tenant_id, location_id, sale_number, status, total_amount)
                VALUES ('%s', '%s', '%s', 'S-0001', 'completed', 79.99)
                """.formatted(sale, t, location));

        stmt.execute("""
                INSERT INTO sale_items (tenant_id, sale_id, product_id, quantity, unit_price)
                VALUES ('%s', '%s', '%s', 1, 79.99)
                """.formatted(t, sale, product));

        stmt.execute("""
                INSERT INTO sale_returns
                    (tenant_id, sale_id, product_id, quantity, restocked, return_group_id)
                VALUES ('%s', '%s', '%s', 1, true, gen_random_uuid())
                """.formatted(t, sale, product));

        stmt.execute("""
                INSERT INTO purchase_orders (id, tenant_id, supplier_id, location_id, po_number, status)
                VALUES ('%s', '%s', '%s', '%s', 'PO-0001', 'ordered')
                """.formatted(purchaseOrder, t, supplier, location));

        stmt.execute("""
                INSERT INTO purchase_order_items
                    (tenant_id, purchase_order_id, product_id, quantity_ordered, unit_cost)
                VALUES ('%s', '%s', '%s', 50, 45.00)
                """.formatted(t, purchaseOrder, product));

        stmt.execute("""
                INSERT INTO supplier_lead_time_observations
                    (tenant_id, supplier_id, purchase_order_id, ordered_at, received_at, lead_time_days)
                VALUES ('%s', '%s', '%s', now() - interval '7 days', now(), 7)
                """.formatted(t, supplier, purchaseOrder));

        stmt.execute("""
                INSERT INTO demand_daily (tenant_id, product_id, location_id, day, units_sold, sales_count)
                VALUES ('%s', '%s', '%s', current_date - 1, 8, 3)
                """.formatted(t, product, location));

        stmt.execute("""
                INSERT INTO forecasts
                    (tenant_id, product_id, location_id, method, history_days,
                     avg_daily_demand, horizon_days, forecast_qty)
                VALUES ('%s', '%s', '%s', 'moving_average', 90, 1.1000, 30, 33.000)
                """.formatted(t, product, location));

        stmt.execute("""
                INSERT INTO forecast_accuracy
                    (tenant_id, forecast_id, product_id, period_start, period_end,
                     predicted_qty, actual_qty)
                SELECT tenant_id, id, product_id, current_date - 30, current_date, forecast_qty, 30.000
                FROM forecasts WHERE tenant_id = '%s'
                """.formatted(t));

        stmt.execute("""
                INSERT INTO reorder_recommendations
                    (tenant_id, product_id, location_id, supplier_id,
                     quantity_on_hand, reorder_point, recommended_qty)
                VALUES ('%s', '%s', '%s', '%s', 12.000, 20.000, 50.000)
                """.formatted(t, product, location, supplier));

        stmt.execute("""
                INSERT INTO audit_log (tenant_id, user_id, action, entity_type, entity_id)
                VALUES ('%s', '%s', 'product.created', 'product', '%s')
                """.formatted(t, user, product));
    }

    @Test
    @DisplayName("the application connection is actually subject to RLS")
    void applicationConnectionIsSubjectToRowLevelSecurity() {
        withTenant(TENANT_A, jdbc -> {
            assertThat(jdbc.queryForObject("SELECT current_user", String.class))
                    .as("reads must go through the restricted role, not the owner")
                    .isEqualTo(APP_ROLE);

            assertThat(jdbc.queryForObject(
                    "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user",
                    Boolean.class))
                    .as("a superuser or BYPASSRLS role ignores every policy, which would make "
                            + "every other assertion in this class pass on a broken schema")
                    .isFalse();
        });
    }

    @Test
    @DisplayName("the fixture actually landed — tenant A can see its own rows in every table")
    void seededTablesAreActuallySeeded() {
        withTenant(TENANT_A, jdbc -> SoftAssertions.assertSoftly(softly -> {
            for (String table : TENANT_SCOPED_TABLES) {
                softly.assertThat(count(jdbc, table))
                        .as("%s: tenant A must see its own seeded row — a zero here means the "
                                + "isolation assertions are passing because nothing is visible "
                                + "at all, not because isolation works", table)
                        .isPositive();
            }
        }));
    }

    @Test
    @DisplayName("bound to tenant A, every table returns zero of tenant B's rows")
    void tenantASeesZeroRowsOfTenantB() {
        withTenant(TENANT_A, jdbc -> SoftAssertions.assertSoftly(softly -> {
            for (String table : TENANT_SCOPED_TABLES) {
                softly.assertThat(countWhereTenantIs(jdbc, table, TENANT_B))
                        .as("%s: tenant B's rows must be invisible to tenant A", table)
                        .isZero();
            }
        }));
    }

    @Test
    @DisplayName("bound to tenant A, no table returns a row belonging to ANY other tenant")
    void tenantASeesNothingBelongingToAnyoneElse() {
        withTenant(TENANT_A, jdbc -> SoftAssertions.assertSoftly(softly -> {
            for (String table : TENANT_SCOPED_TABLES) {
                softly.assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM " + table + " WHERE tenant_id <> ?",
                                Long.class, TENANT_A))
                        .as("%s: leaked rows from a tenant other than A", table)
                        .isZero();
            }
        }));
    }

    @Test
    @DisplayName("with NO tenant bound, every table returns empty rather than everything")
    void withNoTenantBoundEveryTableIsEmpty() {
        withNoTenant(jdbc -> {
            assertThat(jdbc.queryForObject("SELECT current_tenant_id() IS NULL", Boolean.class))
                    .as("no tenant should be bound — if one is, it rode in on a pooled "
                            + "connection from an earlier test and T3 is broken")
                    .isTrue();

            SoftAssertions.assertSoftly(softly -> {
                for (String table : TENANT_SCOPED_TABLES) {
                    softly.assertThat(count(jdbc, table))
                            .as("%s: an unbound connection must see nothing, not everything", table)
                            .isZero();
                }
                softly.assertThat(count(jdbc, "tenants"))
                        .as("tenants: an unbound connection must not enumerate the customer list")
                        .isZero();
            });
        });
    }

    @Test
    @DisplayName("the tenants table shows a tenant only itself")
    void tenantsTableShowsOnlyYourOwnTenant() {
        withTenant(TENANT_A, jdbc -> {
            assertThat(jdbc.queryForList("SELECT id FROM tenants", UUID.class))
                    .as("tenant_self policy: exactly your own row, never the customer list")
                    .containsExactly(TENANT_A);
        });
    }

    @Nested
    @DisplayName("Cross-tenant writes")
    class CrossTenantWrites {

        @Test
        @DisplayName("tenant A cannot insert a row carrying tenant B's id")
        void insertingAnotherTenantsIdIsRejected() {
            withTenant(TENANT_A, jdbc ->
                    assertThatThrownBy(() -> jdbc.update(
                            "INSERT INTO categories (tenant_id, name) VALUES (?, ?)",
                            TENANT_B, "Smuggled Category"))
                            .as("WITH CHECK (tenant_id = current_tenant_id()) must reject this")
                            .rootCause()
                            .hasMessageContaining("violates row-level security policy"));
        }

        @Test
        @DisplayName("and the rejected row is not there afterwards")
        void theRejectedRowWasNotWritten() {
            withTenant(TENANT_B, jdbc ->
                    assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM categories WHERE name = ?",
                            Long.class, "Smuggled Category"))
                            .as("tenant B must not have acquired a category from tenant A")
                            .isZero());
        }
    }

    @Nested
    @DisplayName("Tenant registration on the application pool")
    class Registration {

        @Test
        @DisplayName("succeeds when app.tenant_id is bound to the id being created")
        void registrationWorksOnTheApplicationPool() {
            UUID newTenant = newTenantId();

            withTenant(newTenant, jdbc -> {
                jdbc.update("INSERT INTO tenants (id, slug, name) VALUES (?, ?, ?)",
                        newTenant, "reg-" + newTenant.toString().substring(0, 8), "Newly Registered");

                jdbc.update("""
                        INSERT INTO users (tenant_id, email, full_name, role, status)
                        VALUES (?, ?, 'First Owner', 'owner', 'active')
                        """, newTenant, "owner-" + newTenant + "@example.test");

                assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM tenants WHERE id = ?", Long.class, newTenant))
                        .as("the tenant row must be visible to the transaction that created it")
                        .isEqualTo(1L);
                assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM users WHERE tenant_id = ?", Long.class, newTenant))
                        .as("and so must its first user")
                        .isEqualTo(1L);
            });
        }

        @Test
        @DisplayName("cannot create a tenant under an id other than the one bound")
        void registrationCannotCreateSomeoneElsesTenant() {
            UUID declared = newTenantId();
            UUID someoneElse = newTenantId();

            withTenant(declared, jdbc ->
                    assertThatThrownBy(() -> jdbc.update(
                            "INSERT INTO tenants (id, slug, name) VALUES (?, ?, ?)",
                            someoneElse, "hijack-" + someoneElse.toString().substring(0, 8), "Hijacked"))
                            .as("WITH CHECK (id = current_tenant_id()) confines registration "
                                    + "to the tenant it declared")
                            .rootCause()
                            .hasMessageContaining("violates row-level security policy"));
        }

        @Test
        @DisplayName("a duplicate slug still fails, even though the tenant row is invisible")
        void duplicateSlugIsRejectedDespiteRlsHidingTheExistingRow() {
            UUID first = newTenantId();
            String slug = "dup-" + first.toString().substring(0, 8);

            withTenantCommitting(first, jdbc ->
                    jdbc.update("INSERT INTO tenants (id, slug, name) VALUES (?, ?, ?)",
                            first, slug, "First Claimant"));

            UUID second = newTenantId();
            withTenant(second, jdbc -> {
                assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM tenants WHERE slug = ?", Long.class, slug))
                        .as("the existing tenant is invisible to this transaction — a pre-check "
                                + "would wrongly conclude the slug is free")
                        .isZero();

                assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO tenants (id, slug, name) VALUES (?, ?, ?)",
                        second, slug, "Second Claimant"))
                        .as("but the unique index still refuses it")
                        .rootCause()
                        .hasMessageContaining("duplicate key value violates unique constraint");
            });
        }
    }

    private static long count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private static long countWhereTenantIs(JdbcTemplate jdbc, String table, UUID tenant) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE tenant_id = ?", Long.class, tenant);
    }

    private void withTenant(UUID tenant, Consumer<JdbcTemplate> work) {
        onAppConnection(jdbc -> {
            jdbc.queryForObject(
                    "SELECT set_config('app.tenant_id', ?, true)", String.class, tenant.toString());
            work.accept(jdbc);
        });
    }

    private void withNoTenant(Consumer<JdbcTemplate> work) {
        onAppConnection(work);
    }

    private void withTenantCommitting(UUID tenant, Consumer<JdbcTemplate> work) {
        try (Connection conn = appDataSource.getConnection()) {
            conn.setAutoCommit(false);
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(conn, true));
            jdbc.queryForObject(
                    "SELECT set_config('app.tenant_id', ?, true)", String.class, tenant.toString());
            work.accept(jdbc);
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("could not commit as tenant " + tenant, e);
        }
    }

    private void onAppConnection(Consumer<JdbcTemplate> work) {
        try (Connection conn = appDataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                work.accept(new JdbcTemplate(new SingleConnectionDataSource(conn, true)));
            } finally {
                conn.rollback();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not borrow an application connection", e);
        }
    }
}
