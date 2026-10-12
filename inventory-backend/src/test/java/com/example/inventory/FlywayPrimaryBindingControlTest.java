package com.example.inventory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Flyway binding with two datasources and NO explicit spring.flyway.* connection")
class FlywayPrimaryBindingControlTest {

    private static final String OWNER_ROLE = "inventory_owner";
    private static final String OWNER_PASSWORD = "control_owner_pw";
    private static final String APP_ROLE = "inventory_app";
    private static final String APP_PASSWORD = "control_app_pw";
    private static final String LOGIN_ROLE = "inventory_login";
    private static final String LOGIN_PASSWORD = "control_login_pw";

    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("inventory")
                    .withUsername(OWNER_ROLE)
                    .withPassword(OWNER_PASSWORD);

    static {
        POSTGRES.start();
        createRoles();
    }

    private static void createRoles() {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), OWNER_ROLE, OWNER_PASSWORD);
             Statement stmt = conn.createStatement()) {

            stmt.execute("CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD '" + APP_PASSWORD + "' "
                    + "NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE");
            stmt.execute("GRANT CREATE, USAGE ON SCHEMA public TO " + APP_ROLE);

            stmt.execute("CREATE ROLE " + LOGIN_ROLE + " LOGIN PASSWORD '" + LOGIN_PASSWORD + "' "
                    + "NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOINHERIT");
            stmt.execute("GRANT USAGE ON SCHEMA public TO " + LOGIN_ROLE);
        } catch (Exception e) {
            throw new IllegalStateException("could not prepare the control database", e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.locations", () -> "classpath:db/control");

        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("app.datasource.login.url", POSTGRES::getJdbcUrl);
        registry.add("app.datasource.login.username", () -> LOGIN_ROLE);
        registry.add("app.datasource.login.password", () -> LOGIN_PASSWORD);
    }

    private static List<String> queryAsOwner(String sql) {
        List<String> values = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), OWNER_ROLE, OWNER_PASSWORD);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        } catch (Exception e) {
            throw new IllegalStateException("owner query failed: " + sql, e);
        }
        return values;
    }

    @Test
    @DisplayName("without the explicit properties Flyway silently migrates as the PRIMARY pool's role")
    void withoutExplicitPropertiesFlywayUsesThePrimaryDataSource() {
        List<String> installedBy =
                queryAsOwner("SELECT DISTINCT installed_by FROM flyway_schema_history");

        assertThat(installedBy)
                .as("the control migration should have been applied — if this is empty, "
                        + "Flyway did not run and the control proves nothing")
                .isNotEmpty();

        assertThat(installedBy)
                .as("THIS is the hazard: with no explicit spring.flyway.* connection, Boot binds "
                        + "Flyway to the @Primary datasource, which is the RLS-bound application "
                        + "pool. If this ever starts reporting inventory_owner, the guard in "
                        + "FlywayBindingTest has stopped testing anything.")
                .containsExactly(APP_ROLE);
    }

    @Test
    @DisplayName("and it succeeds — the misbinding does not announce itself")
    void theMisbindingIsSilent() {
        assertThat(queryAsOwner(
                "SELECT bool_and(success)::text FROM flyway_schema_history WHERE version IS NOT NULL"))
                .as("the migration applied cleanly as the wrong role: no exception, no failed "
                        + "history row. Only installed_by records what happened.")
                .containsExactly("true");

        assertThat(queryAsOwner(
                "SELECT (to_regclass('public.control_probe') IS NOT NULL)::text"))
                .as("the table really was created, so this is a genuine successful run")
                .containsExactly("true");
    }
}
