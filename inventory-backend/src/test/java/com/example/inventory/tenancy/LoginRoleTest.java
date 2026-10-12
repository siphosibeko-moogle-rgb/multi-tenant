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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.example.inventory.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("The login role (V3)")
class LoginRoleTest extends AbstractIntegrationTest {

    private static final List<String> FORBIDDEN_TABLES = List.of(
            "refresh_tokens", "locations", "categories", "suppliers", "products",
            "product_suppliers", "stock_movements", "product_stock", "stocktakes",
            "stocktake_lines", "sales", "sale_items", "purchase_orders", "purchase_order_items",
            "supplier_lead_time_observations", "demand_daily", "forecasts", "forecast_accuracy",
            "reorder_recommendations", "audit_log",
            "sale_returns",
            "change_log");

    private static final UUID TENANT = newTenantId();
    private static final String EMAIL = "login-probe-" + UUID.randomUUID() + "@example.test";

    private static boolean seeded;

    @Autowired
    @Qualifier("loginDataSource")
    private DataSource loginDataSource;

    @BeforeEach
    void seedOneTenant() throws SQLException {
        if (seeded) {
            return;
        }
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {

            stmt.execute("INSERT INTO tenants (id, slug, name) VALUES ('%s', 'lg-%s', 'Login Probe')"
                    .formatted(TENANT, TENANT.toString().substring(0, 8)));
            stmt.execute("""
                    INSERT INTO users (tenant_id, email, full_name, role, status)
                    VALUES ('%s', '%s', 'Login Probe User', 'owner', 'active')
                    """.formatted(TENANT, EMAIL));
        }
        seeded = true;
    }

    @Test
    @DisplayName("is neither a superuser nor BYPASSRLS, and does not inherit the app role")
    void roleAttributesAreAsNarrowAsV3Claims() {
        onLoginConnection(jdbc -> {
            assertThat(jdbc.queryForObject("SELECT current_user", String.class))
                    .as("the login pool must connect as the login role, not something else")
                    .isEqualTo(LOGIN_ROLE);

            var attrs = jdbc.queryForMap(
                    "SELECT rolsuper, rolbypassrls, rolinherit FROM pg_roles WHERE rolname = current_user");

            assertThat((Boolean) attrs.get("rolsuper"))
                    .as("a superuser ignores every policy in the schema")
                    .isFalse();
            assertThat((Boolean) attrs.get("rolbypassrls"))
                    .as("BYPASSRLS on THIS role would be the worst case of all: it reads users "
                            + "unscoped already, and BYPASSRLS would extend that to every table")
                    .isFalse();
            assertThat((Boolean) attrs.get("rolinherit"))
                    .as("NOINHERIT — no privileges may arrive through role membership")
                    .isFalse();

            assertThat(jdbc.queryForObject(
                    "SELECT pg_has_role(current_user, 'inventory_app', 'MEMBER')", Boolean.class))
                    .as("the login role must not be a member of the application role by any path")
                    .isFalse();
        });
    }

    @Test
    @DisplayName("reads tenants and users with no tenant bound — this is the whole point")
    void canReadTenantsAndUsersUnbound() {
        onLoginConnection(jdbc -> {
            assertThat(jdbc.queryForObject("SELECT current_tenant_id() IS NULL", Boolean.class))
                    .as("nothing is bound: this is the pre-authentication state login runs in")
                    .isTrue();

            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM users WHERE email = ?", Long.class, EMAIL))
                    .as("the email lookup that makes login possible must return the row")
                    .isEqualTo(1L);

            assertThat(jdbc.queryForObject(
                    "SELECT tenant_id FROM users WHERE email = ?", UUID.class, EMAIL))
                    .as("and it must resolve to the right tenant")
                    .isEqualTo(TENANT);

            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM tenants WHERE id = ?", Long.class, TENANT))
                    .as("the tenant row itself must be readable, to check status before issuing a token")
                    .isEqualTo(1L);
        });
    }

    @Nested
    @DisplayName("Reach of the role")
    class ReachOfTheRole {

        @Test
        @DisplayName("cannot read ANY other tenant-scoped table — and fails on privilege, not emptiness")
        void cannotReadAnyOtherTenantScopedTable() {
            SoftAssertions.assertSoftly(softly -> {
                for (String table : FORBIDDEN_TABLES) {
                    softly.assertThatThrownBy(() -> onFreshLoginConnection(
                                    jdbc -> jdbc.queryForObject(
                                            "SELECT count(*) FROM " + table, Long.class)))
                            .as("%s: must be refused at the privilege layer. An empty result here "
                                    + "would mean the role can reach the table and is only being "
                                    + "filtered by policy — one migration away from reading it.",
                                    table)
                            .rootCause()
                            .hasMessageContaining("permission denied for table " + table);
                }
            });
        }

        @Test
        @DisplayName("cannot write to tenants or users")
        void cannotWriteToTheTwoTablesItCanRead() {
            SoftAssertions.assertSoftly(softly -> {
                softly.assertThatThrownBy(() -> onFreshLoginConnection(jdbc -> jdbc.update(
                                "INSERT INTO tenants (id, slug, name) VALUES (?, ?, ?)",
                                UUID.randomUUID(), "smuggled", "Smuggled Tenant")))
                        .as("login_read is FOR SELECT; there is no INSERT grant and no WITH CHECK "
                                + "that could accept this row")
                        .rootCause()
                        .hasMessageContaining("permission denied for table tenants");

                softly.assertThatThrownBy(() -> onFreshLoginConnection(jdbc -> jdbc.update(
                                "UPDATE users SET full_name = ? WHERE email = ?", "Renamed", EMAIL)))
                        .as("a role that could rewrite users could rewrite password hashes")
                        .rootCause()
                        .hasMessageContaining("permission denied for table users");

                softly.assertThatThrownBy(() -> onFreshLoginConnection(jdbc -> jdbc.update(
                                "DELETE FROM users WHERE email = ?", EMAIL)))
                        .as("nor delete them")
                        .rootCause()
                        .hasMessageContaining("permission denied for table users");
            });
        }

        @Test
        @DisplayName("holds no privilege it was not granted, by PostgreSQL's own accounting")
        void privilegeCatalogueAgreesWithV3() {
            onLoginConnection(jdbc -> SoftAssertions.assertSoftly(softly -> {
                for (String verb : List.of("INSERT", "UPDATE", "DELETE", "TRUNCATE")) {
                    for (String table : List.of("tenants", "users")) {
                        softly.assertThat(jdbc.queryForObject(
                                        "SELECT has_table_privilege(current_user, ?, ?)",
                                        Boolean.class, table, verb))
                                .as("%s on %s must not be granted to the login role", verb, table)
                                .isFalse();
                    }
                }
                for (String table : List.of("tenants", "users")) {
                    softly.assertThat(jdbc.queryForObject(
                                    "SELECT has_table_privilege(current_user, ?, 'SELECT')",
                                    Boolean.class, table))
                            .as("SELECT on %s is the one privilege this role should hold", table)
                            .isTrue();
                }
            }));
        }
    }

    @Test
    @DisplayName("login_read is scoped to the login role and invisible to the app role")
    void loginReadDoesNotApplyToTheApplicationRole() {
        var policies = new JdbcTemplate(appDataSource).queryForList(
                "SELECT tablename, policyname, roles::text, cmd FROM pg_policies "
                        + "WHERE schemaname = 'public' AND policyname = 'login_read' "
                        + "ORDER BY tablename");

        assertThat(policies)
                .as("V3 creates login_read on exactly tenants and users")
                .hasSize(2);

        SoftAssertions.assertSoftly(softly -> {
            for (var policy : policies) {
                softly.assertThat((String) policy.get("roles"))
                        .as("%s.login_read must name inventory_login and nothing else — "
                                + "{public} here would open the table to every role",
                                policy.get("tablename"))
                        .isEqualTo("{" + LOGIN_ROLE + "}");
                softly.assertThat((String) policy.get("cmd"))
                        .as("%s.login_read must be SELECT-only, so it contributes no WITH CHECK",
                                policy.get("tablename"))
                        .isEqualTo("SELECT");
            }
        });
    }

    @Autowired
    private DataSource appDataSource;

    private void onFreshLoginConnection(Consumer<JdbcTemplate> work) {
        onLoginConnection(work);
    }

    private void onLoginConnection(Consumer<JdbcTemplate> work) {
        try (Connection conn = loginDataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                work.accept(new JdbcTemplate(new SingleConnectionDataSource(conn, true)));
            } finally {
                conn.rollback();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not borrow a login connection", e);
        }
    }
}
