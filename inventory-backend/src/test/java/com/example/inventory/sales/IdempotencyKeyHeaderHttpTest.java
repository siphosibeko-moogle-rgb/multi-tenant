package com.example.inventory.sales;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
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

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Idempotency-Key header")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdempotencyKeyHeaderHttpTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    private static UUID tenantId;
    private static UUID userId;
    private static UUID productId;
    private static boolean seeded;

    @BeforeEach
    void seed() throws SQLException {
        if (seeded) {
            return;
        }
        tenantId = newTenantId();
        userId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        productId = UUID.randomUUID();
        String tag = tenantId.toString().substring(0, 8);

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO tenants (id, slug, name) VALUES ('%s', 'idm-%s', 'Shop')"
                    .formatted(tenantId, tag));
            stmt.execute("""
                    INSERT INTO users (id, tenant_id, email, full_name, role, status)
                    VALUES ('%s', '%s', 'o-%s@example.test', 'Olu Owner', 'owner', 'active')
                    """.formatted(userId, tenantId, tag));
            stmt.execute("INSERT INTO locations (id, tenant_id, name, is_default) "
                    + "VALUES ('%s', '%s', 'Main', true)".formatted(locationId, tenantId));
            stmt.execute("""
                    INSERT INTO products (id, tenant_id, sku, name, selling_price)
                    VALUES ('%s', '%s', 'SKU-A', 'Bread', 12.50)
                    """.formatted(productId, tenantId));
        }
        seeded = true;

        var r = http().postWithToken("/inventory/adjustments", """
                {"productId":"%s","quantityDelta":100,"reason":"opening"}
                """.formatted(productId), token());
        assertThat(r.status()).as("fixture stocking must succeed").isEqualTo(201);
    }

    private HttpTestClient http() {
        return new HttpTestClient(port);
    }

    private String token() {
        return jwtService.issueAccessToken(tenantId, userId, "owner").value();
    }

    private HttpResponse<String> postWithIdempotencyHeader(String body, String key) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + HttpTestClient.BASE_PATH + "/sales"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token())
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (key != null) {
                request.header("Idempotency-Key", key);
            }
            return HttpClient.newHttpClient()
                    .send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("request failed", e);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("a replay carrying only the header returns the original sale and moves stock once")
    void headerAloneSatisfiesIdempotency() {
        String key = UUID.randomUUID().toString();
        String body = """
                {"lines":[{"productId":"%s","quantity":3}]}
                """.formatted(productId);

        var first = postWithIdempotencyHeader(body, key);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        String firstId = JSON.readTree(first.body()).get("id").asString();

        var replay = postWithIdempotencyHeader(body, key);
        assertThat(replay.statusCode()).as(replay.body()).isEqualTo(200);
        String replayId = JSON.readTree(replay.body()).get("id").asString();

        assertThat(replayId).as("a header-only replay must return the original sale")
                .isEqualTo(firstId);
    }
}
