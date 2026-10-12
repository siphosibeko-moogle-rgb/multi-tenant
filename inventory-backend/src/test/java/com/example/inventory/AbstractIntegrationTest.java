package com.example.inventory;

import java.util.UUID;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    private static final String DATABASE_NAME = "inventory";

    private static final String OWNER_ROLE = "inventory_owner";
    private static final String OWNER_PASSWORD = "test_owner_only";

    protected static final String APP_ROLE = "inventory_app";
    protected static final String APP_PASSWORD = "test_app_only";

    protected static final String LOGIN_ROLE = "inventory_login";
    protected static final String LOGIN_PASSWORD = "test_login_only";

    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName(DATABASE_NAME)
                    .withUsername(OWNER_ROLE)
                    .withPassword(OWNER_PASSWORD);

    static {
        POSTGRES.start();
    }

    protected static UUID newTenantId() {
        return UUID.randomUUID();
    }

    @DynamicPropertySource
    static void registerDatasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.appUserPassword", () -> APP_PASSWORD);
        registry.add("spring.flyway.placeholders.loginUserPassword", () -> LOGIN_PASSWORD);

        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);

        registry.add("app.datasource.login.url", POSTGRES::getJdbcUrl);
        registry.add("app.datasource.login.username", () -> LOGIN_ROLE);
        registry.add("app.datasource.login.password", () -> LOGIN_PASSWORD);
    }
}
