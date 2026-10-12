package com.example.inventory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Flyway binding with two datasources")
class FlywayBindingTest {

    private static final String OWNER_ROLE = "inventory_owner";
    private static final String OWNER_PASSWORD = "binding_owner_pw";
    private static final String APP_ROLE = "inventory_app";
    private static final String APP_PASSWORD = "binding_app_pw";
    private static final String LOGIN_ROLE = "inventory_login";
    private static final String LOGIN_PASSWORD = "binding_login_pw";

    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("inventory")
                    .withUsername(OWNER_ROLE)
                    .withPassword(OWNER_PASSWORD);

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> OWNER_ROLE);
        registry.add("spring.flyway.password", () -> OWNER_PASSWORD);
        registry.add("spring.flyway.placeholders.appUserPassword", () -> APP_PASSWORD);
        registry.add("spring.flyway.placeholders.loginUserPassword", () -> LOGIN_PASSWORD);

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
    @DisplayName("migrations run as the schema owner, never as the application role")
    void migrationsRunAsTheSchemaOwner() {
        List<String> installedBy =
                queryAsOwner("SELECT DISTINCT installed_by FROM flyway_schema_history");

        assertThat(installedBy)
                .as("flyway_schema_history should record who applied each migration")
                .isNotEmpty();

        assertThat(installedBy)
                .as("migrations must never be applied by the RLS-bound application role")
                .doesNotContain(APP_ROLE);

        assertThat(installedBy)
                .as("migrations must be applied by the schema owner only")
                .containsExactly(OWNER_ROLE);
    }

    @Test
    @DisplayName("every migration actually applied on this context's own database")
    void migrationsWereAppliedHere() {
        List<String> versions = queryAsOwner(
                "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL "
                        + "ORDER BY installed_rank");

        assertThat(versions)
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11",
                        "12", "13");
    }

    @Test
    @DisplayName("the primary pool is the application role, so the setup is genuinely hostile")
    void primaryPoolIsTheApplicationRole(@Autowired DataSource primary) {
        String user = new JdbcTemplate(primary).queryForObject("SELECT current_user", String.class);

        assertThat(user)
                .as("if the primary pool were the owner, this test would prove nothing")
                .isEqualTo(APP_ROLE);
    }

    @Test
    @DisplayName("the login pool is a second, distinct datasource bean")
    void theLoginPoolIsASecondDataSource(@Autowired DataSource primary) {
        String loginUser = new JdbcTemplate(loginDataSource)
                .queryForObject("SELECT current_user", String.class);

        assertThat(loginUser).isEqualTo(LOGIN_ROLE);
        assertThat(loginDataSource)
                .as("two distinct pools, not one bean injected twice")
                .isNotSameAs(primary);
    }

    @Autowired
    @Qualifier("loginDataSource")
    private DataSource loginDataSource;

    @Test
    @DisplayName("the Flyway bean itself holds an owner connection, not either pool")
    void flywayBeanIsBoundToTheOwnerConnection(@Autowired Flyway flyway) {
        DataSource flywayDataSource = flyway.getConfiguration().getDataSource();

        assertThat(flywayDataSource)
                .as("Flyway must have a datasource of its own, distinct from the pool beans")
                .isNotNull();

        String user = new JdbcTemplate(flywayDataSource)
                .queryForObject("SELECT current_user", String.class);

        assertThat(user)
                .as("spring.flyway.url/user/password must win over the @Primary DataSource bean")
                .isEqualTo(OWNER_ROLE);
    }
}
