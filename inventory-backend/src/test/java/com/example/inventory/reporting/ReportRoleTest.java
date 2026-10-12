package com.example.inventory.reporting;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

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

@DisplayName("Report role gates")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReportRoleTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    private static ReportFixture shop;

    @BeforeEach
    void seedOnce() throws SQLException {
        if (shop == null) {
            shop = ReportFixture.create(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                    POSTGRES.getPassword(), http(),
                    (tenantId, userId) -> jwtService
                            .issueAccessToken(tenantId, userId, "owner").value());
        }
    }

    private HttpTestClient http() {
        return new HttpTestClient(port);
    }

    private String tokenFor(String role) {
        return jwtService.issueAccessToken(shop.tenantId, shop.userId, role).value();
    }

    private static List<String> endpoints() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return List.of(
                "/reports/dashboard",
                "/reports/sales-summary?from=%s&to=%s".formatted(today.minusDays(6), today),
                "/reports/inventory-valuation",
                "/reports/top-products");
    }

    @Test
    @DisplayName("an owner may read every report")
    void ownerIsAllowed() {
        for (String endpoint : endpoints()) {
            assertThat(http().get(endpoint, tokenFor("owner")).status())
                    .as("owner on %s", endpoint)
                    .isEqualTo(200);
        }
    }

    @Test
    @DisplayName("a manager may read every report — the contract names reports as theirs")
    void managerIsAllowed() {
        for (String endpoint : endpoints()) {
            assertThat(http().get(endpoint, tokenFor("manager")).status())
                    .as("manager on %s", endpoint)
                    .isEqualTo(200);
        }
    }

    @Test
    @DisplayName("a clerk is refused every report")
    void clerkIsRefused() {
        for (String endpoint : endpoints()) {
            HttpTestClient.Response response = http().get(endpoint, tokenFor("clerk"));

            assertThat(response.status())
                    .as("clerk on %s — a clerk records sales; margin is not one of "
                            + "their three jobs", endpoint)
                    .isEqualTo(403);
            assertThat(response.body())
                    .as("a 403 with no body cannot be reported to a user")
                    .isNotBlank();
            assertThat(response.headers().first("Content-Type"))
                    .startsWith("application/problem+json");
        }
    }

    @Test
    @DisplayName("a viewer is refused every report")
    void viewerIsRefused() {
        for (String endpoint : endpoints()) {
            assertThat(http().get(endpoint, tokenFor("viewer")).status())
                    .as("viewer on %s — \"read only\" grants a mode, not a scope, and "
                            + "these bodies disclose cost and margin", endpoint)
                    .isEqualTo(403);
        }
    }

    @Test
    @DisplayName("an unauthenticated request is 401 with a problem body")
    void unauthenticatedIsRefused() {
        for (String endpoint : endpoints()) {
            HttpTestClient.Response response = http().get(endpoint, null);

            assertThat(response.status()).as("anonymous on %s", endpoint).isEqualTo(401);
            assertThat(response.headers().first("Content-Type"))
                    .startsWith("application/problem+json");
        }
    }

    @Test
    @DisplayName("the reports an owner is allowed to read are not empty")
    void theAllowedCaseIsNotVacuous() {
        assertThat(http().get("/reports/dashboard", tokenFor("owner")).json()
                .get("salesTotal").decimalValue())
                .isEqualByComparingTo(ReportFixture.EXPECTED_REVENUE);
        assertThat(http().get("/reports/top-products", tokenFor("manager")).json().size())
                .isGreaterThan(0);
    }
}
