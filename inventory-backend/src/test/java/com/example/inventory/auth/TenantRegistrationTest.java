package com.example.inventory.auth;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.inventory.AbstractIntegrationTest;
import com.example.inventory.auth.AuthDtos.TenantRegistrationRequest;
import com.example.inventory.tenancy.TenantContext;
import com.example.inventory.web.ConflictException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Tenant registration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TenantRegistrationTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    @Qualifier("appDataSource")
    private DataSource appDataSource;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TokenIssuer tokenIssuer;

    @Autowired
    private TenantRegistrationService registrationService;

    private static String uniqueSlug() {
        return "reg-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static TenantRegistrationRequest request(String slug) {
        return new TenantRegistrationRequest(
                "Acme Trading", slug, slug + "@example.test", "correct-horse-battery",
                "Ada Owner", "ZAR", "UTC");
    }

    private static <T> T asOwner(String sql, Class<T> type, Object... args) {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            return new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                    conn, true)).queryForObject(sql, type, args);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Nested
    @DisplayName("The tenant id comes from the server")
    class TenantIdOrigin {

        @Test
        @DisplayName("a tenantId in the request body is ignored, not honoured")
        void tenantIdInThePayloadIsIgnored() throws Exception {
            UUID attackerChosenId = UUID.randomUUID();
            String slug = uniqueSlug();

            String payload = """
                    {
                      "businessName": "Acme Trading",
                      "slug": "%s",
                      "ownerEmail": "%s@example.test",
                      "ownerPassword": "correct-horse-battery",
                      "ownerName": "Ada Owner",
                      "tenantId": "%s",
                      "tenant_id": "%s",
                      "id": "%s"
                    }
                    """.formatted(slug, slug, attackerChosenId, attackerChosenId, attackerChosenId);

            var response = new HttpTestClient(port).post("/auth/register-tenant", payload);
            assertThat(response.status()).isEqualTo(201);

            String createdId = response.at("/user/tenant/id");
            assertThat(createdId).isNotBlank();

            assertThat(UUID.fromString(createdId))
                    .as("the tenant must NOT have been created under the id the caller supplied — "
                            + "if it were, a caller could name an existing tenant and have RLS "
                            + "accept an owner account inside it")
                    .isNotEqualTo(attackerChosenId);

            assertThat(asOwner("SELECT count(*) FROM tenants WHERE id = ?", Long.class,
                    attackerChosenId))
                    .as("nothing at all should exist under the caller's chosen id")
                    .isZero();
        }

        @Test
        @DisplayName("registration binds a tenant and always clears it afterwards")
        void theRegistrationBindingDoesNotOutliveTheCall() {
            assertThat(TenantContext.current()).as("precondition").isEmpty();

            registrationService.register(request(uniqueSlug()));

            assertThat(TenantContext.current())
                    .as("a tenant left bound on this thread would become the next request's "
                            + "tenant — the thread is pooled")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Insert ordering")
    class InsertOrdering {

        @Test
        @DisplayName("a primary key collision aborts before any user row is written")
        void pkCollisionAbortsBeforeAnyUserRowIsWritten() throws SQLException {
            UUID existingTenant = newTenantId();
            String victimSlug = uniqueSlug();
            try (Connection conn = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO tenants (id, slug, name) VALUES ('%s', '%s', 'Victim Ltd')"
                        .formatted(existingTenant, victimSlug));
            }

            long usersBefore = asOwner("SELECT count(*) FROM users WHERE tenant_id = ?",
                    Long.class, existingTenant);

            var recording = new RecordingDataSource(appDataSource);
            var collidingService = new TenantRegistrationService(
                    recording, transactions, passwordEncoder, tokenIssuer, () -> existingTenant);

            assertThatThrownBy(() -> collidingService.register(request(uniqueSlug())))
                    .as("the tenants insert must collide and abort the transaction")
                    .isInstanceOf(ConflictException.class);

            assertThat(recording.sawAny("INSERT INTO users"))
                    .as("the users insert must never have been ATTEMPTED. The tenants insert "
                            + "collides on the primary key, and everything after it must be "
                            + "unreachable — otherwise, the moment those two inserts stop sharing "
                            + "a transaction, registration becomes a way to add an owner account "
                            + "to somebody else's business.")
                    .isFalse();

            assertThat(recording.indexOf("INSERT INTO tenants"))
                    .as("and the tenants insert must have been attempted, or this test proves "
                            + "nothing about ordering")
                    .isNotNegative();

            assertThat(asOwner("SELECT count(*) FROM users WHERE tenant_id = ?",
                    Long.class, existingTenant))
                    .as("and no user row for the victim tenant survives — atomicity, the second "
                            + "line of defence behind the ordering")
                    .isEqualTo(usersBefore);

            assertThat(TenantContext.current())
                    .as("the binding must be cleared even when the transaction fails")
                    .isEmpty();
        }

        @Test
        @DisplayName("on the happy path, tenants is inserted before users")
        void tenantsIsInsertedBeforeUsers() {
            var recording = new RecordingDataSource(appDataSource);
            var service = new TenantRegistrationService(
                    recording, transactions, passwordEncoder, tokenIssuer, UUID::randomUUID);

            service.register(request(uniqueSlug()));

            int tenantsAt = recording.indexOf("INSERT INTO tenants");
            int usersAt = recording.indexOf("INSERT INTO users");

            assertThat(tenantsAt).as("the tenants insert should have run").isNotNegative();
            assertThat(usersAt).as("the users insert should have run").isNotNegative();
            assertThat(tenantsAt)
                    .as("tenants must be written FIRST — it is the insert that collides on a "
                            + "duplicate id, and everything protecting another tenant's data "
                            + "depends on it running before any user row is attempted")
                    .isLessThan(usersAt);
        }

        @Test
        @DisplayName("a duplicate slug is a 409 and writes nothing")
        void duplicateSlugIsRejectedAndWritesNothing() {
            String slug = uniqueSlug();
            registrationService.register(request(slug));

            long tenantsWithSlug = asOwner(
                    "SELECT count(*) FROM tenants WHERE slug = ?::citext", Long.class, slug);
            long usersBefore = asOwner("SELECT count(*) FROM users", Long.class);

            assertThatThrownBy(() -> registrationService.register(request(slug)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("already exists");

            assertThat(asOwner("SELECT count(*) FROM tenants WHERE slug = ?::citext",
                    Long.class, slug))
                    .as("the second attempt must not have created a second tenant")
                    .isEqualTo(tenantsWithSlug);
            assertThat(asOwner("SELECT count(*) FROM users", Long.class))
                    .as("nor a second owner")
                    .isEqualTo(usersBefore);
        }
    }

    @Test
    @DisplayName("creates the tenant and its owner, and returns a usable token pair")
    void registrationCreatesTenantAndOwner() {
        String slug = uniqueSlug();

        var tokens = registrationService.register(request(slug));

        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(tokens.refreshToken()).isNotBlank();
        assertThat(tokens.tokenType()).isEqualTo("Bearer");
        assertThat(tokens.expiresIn()).isEqualTo(900);
        assertThat(tokens.user().role()).isEqualTo("owner");
        assertThat(tokens.user().tenant().slug()).isEqualTo(slug);

        UUID tenantId = tokens.user().tenant().id();
        assertThat(asOwner("SELECT count(*) FROM users WHERE tenant_id = ? AND role = 'owner'",
                Long.class, tenantId))
                .as("exactly one owner")
                .isEqualTo(1L);

        assertThat(asOwner("SELECT password_hash FROM users WHERE tenant_id = ?",
                String.class, tenantId))
                .as("the password must be stored as a bcrypt hash, never in the clear")
                .startsWith("$2");
    }

    @Test
    @DisplayName("creates a default location, so the business can hold stock immediately")
    void registrationCreatesADefaultLocation() {
        var tokens = registrationService.register(request(uniqueSlug()));
        UUID tenantId = tokens.user().tenant().id();

        assertThat(asOwner(
                "SELECT count(*) FROM locations WHERE tenant_id = ? AND is_default",
                Long.class, tenantId))
                .as("exactly one default location — two would make the fallback ambiguous")
                .isEqualTo(1L);

        assertThat(tokens.user().defaultLocationId())
                .as("the registration response must name the location it created")
                .isNotNull();
        assertThat(asOwner("SELECT id FROM locations WHERE tenant_id = ? AND is_default",
                UUID.class, tenantId))
                .isEqualTo(tokens.user().defaultLocationId());
    }
}
