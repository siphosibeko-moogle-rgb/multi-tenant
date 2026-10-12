package com.example.inventory.forecasting;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.example.inventory.AbstractIntegrationTest;
import com.example.inventory.auth.HttpTestClient;
import com.example.inventory.auth.JwtService;
import com.example.inventory.seed.TenantSeeder;
import com.example.inventory.seed.TenantSeeder.SeededTenant;
import com.example.inventory.tenancy.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Forecasting role gates")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ForecastRoleTest extends AbstractIntegrationTest {

    private static final List<String> ALL_ROLES = List.of("owner", "manager", "clerk", "viewer");
    private static final List<String> WRITE_ROLES = List.of("owner", "manager");

    @LocalServerPort
    private int port;

    @Autowired
    private TenantSeeder seeder;

    @Autowired
    private DemandRollupJob rollup;

    @Autowired
    private ReorderService reorderService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private org.springframework.security.oauth2.jwt.JwtEncoder jwtEncoder;

    @Autowired
    private com.example.inventory.auth.AuthProperties authProperties;

    @Autowired
    private com.example.inventory.inventory.StockLedgerService ledger;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("appDataSource")
    private javax.sql.DataSource appDataSource;

    private static SeededTenant tenant;
    private static UUID openRecommendationId;
    private static boolean prepared;

    @BeforeEach
    void seed() throws Exception {
        if (prepared) {
            return;
        }
        String tag = UUID.randomUUID().toString().substring(0, 8);
        tenant = seeder.seedTenant("Role Bakery", "role-" + tag, 1L, 10);
        asTenant(() -> {
            rollup.rollUp();
            java.math.BigDecimal onHand = new org.springframework.jdbc.core.JdbcTemplate(
                    appDataSource).queryForObject("""
                            SELECT COALESCE(sum(quantity_on_hand), 0) FROM product_stock
                            WHERE product_id = ? AND location_id = ?
                            """, java.math.BigDecimal.class,
                    tenant.productIdsByShape().get("steady"), tenant.locationId());
            ledger.post(new com.example.inventory.inventory.StockLedgerService.MovementRequest(
                    tenant.productIdsByShape().get("steady"), tenant.locationId(),
                    "adjustment", java.math.BigDecimal.ONE.subtract(onHand), null, null, null,
                    "role fixture write-off",
                    java.time.LocalDate.now().atTime(14, 0)
                            .atOffset(java.time.ZoneOffset.UTC)));
            return reorderService.recomputeAll();
        });
        prepared = true;
    }

    private <T> T asTenant(Callable<T> work) throws Exception {
        TenantContext.bind(new TenantContext.TenantIdentity(
                tenant.tenantId(), tenant.ownerId(), "owner"));
        try {
            return work.call();
        } finally {
            TenantContext.clear();
        }
    }

    private HttpTestClient http() {
        return new HttpTestClient(port);
    }

    private String tokenFor(String role) {
        return jwtService.issueAccessToken(tenant.tenantId(), tenant.ownerId(), role).value();
    }

    private String someRecommendationId() {
        if (openRecommendationId == null) {
            var items = http().get("/reorder-recommendations", tokenFor("owner"))
                    .json().get("items");
            openRecommendationId = items.isEmpty() ? UUID.randomUUID()
                    : UUID.fromString(items.get(0).get("id").asString());
        }
        return openRecommendationId.toString();
    }

    private static boolean isAllowed(int status) {
        return status != 403;
    }

    @Test
    @DisplayName("every role may read forecasts, including viewer")
    void readingForecastsIsOpenToEveryRole() {
        SoftAssertions.assertSoftly(softly -> {
            for (String role : ALL_ROLES) {
                softly.assertThat(http().get("/forecasts", tokenFor(role)).status())
                        .as("%s reading /forecasts", role)
                        .isEqualTo(200);
                softly.assertThat(http().get(
                        "/products/" + tenant.productIdsByShape().get("steady") + "/forecast",
                        tokenFor(role)).status())
                        .as("%s reading one product's forecast", role)
                        .isEqualTo(200);
                softly.assertThat(http().get("/reorder-recommendations", tokenFor(role)).status())
                        .as("%s reading the reorder list", role)
                        .isEqualTo(200);
            }
        });
    }

    @Test
    @DisplayName("only owner and manager may recompute — clerk and viewer are refused")
    void recomputeIsGated() {
        SoftAssertions.assertSoftly(softly -> {
            for (String role : ALL_ROLES) {
                int status = http().postWithToken("/forecasts/recompute", "{}", tokenFor(role))
                        .status();
                if (WRITE_ROLES.contains(role)) {
                    softly.assertThat(status).as("%s must be allowed to recompute", role)
                            .isEqualTo(202);
                } else {
                    softly.assertThat(status)
                            .as("%s must be refused — a gate that accidentally permits "
                                    + "everyone passes a happy-path test perfectly", role)
                            .isEqualTo(403);
                }
            }
        });
    }

    @Test
    @DisplayName("only owner and manager may dismiss — clerk and viewer are refused")
    void dismissIsGated() {
        SoftAssertions.assertSoftly(softly -> {
            for (String role : List.of("clerk", "viewer")) {
                softly.assertThat(http().postWithToken(
                        "/reorder-recommendations/" + someRecommendationId() + "/dismiss",
                        "{}", tokenFor(role)).status())
                        .as("%s must not be able to dismiss the shop's reorder advice", role)
                        .isEqualTo(403);
            }
        });

        assertThat(http().postWithToken(
                "/reorder-recommendations/" + someRecommendationId() + "/dismiss",
                "{}", tokenFor("manager")).status())
                .as("a manager must be able to — purchasing is the manager's remit")
                .isEqualTo(204);
    }

    @Test
    @DisplayName("a token with no role claim is refused everywhere, not defaulted")
    void aClaimlessTokenIsRefused() {
        java.time.Instant now = java.time.Instant.now();
        String claimless = jwtEncoder.encode(
                org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                        org.springframework.security.oauth2.jwt.JwsHeader.with(
                                com.example.inventory.auth.AuthProperties.SIGNING_ALGORITHM)
                                .build(),
                        org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
                                .issuer(authProperties.issuer())
                                .issuedAt(now)
                                .expiresAt(now.plusSeconds(300))
                                .subject(tenant.ownerId().toString())
                                .id(UUID.randomUUID().toString())
                                .claim("tid", tenant.tenantId().toString())
                                .build())).getTokenValue();

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(isAllowed(http().get("/forecasts", claimless).status()))
                    .as("no role must mean no access, not the most permissive one")
                    .isFalse();
            softly.assertThat(isAllowed(http().postWithToken(
                    "/forecasts/recompute", "{}", claimless).status())).isFalse();
            softly.assertThat(isAllowed(http().get("/reorder-recommendations", claimless)
                    .status())).isFalse();
        });
    }

    @Test
    @DisplayName("an unauthenticated request is 401 with a problem body, not 403")
    void unauthenticatedIsRefusedBeforeTheRoleGate() {
        HttpTestClient.Response response = http().get("/forecasts", null);

        assertThat(response.status())
                .as("no credentials is an authentication problem, not an authorisation one — "
                        + "and the client's correct response differs (log in vs give up)")
                .isEqualTo(401);
        assertThat(response.headers().first("Content-Type"))
                .as("M3 fixed a bodiless 401; the contract declares a Problem here")
                .startsWith("application/problem+json");
    }
}
