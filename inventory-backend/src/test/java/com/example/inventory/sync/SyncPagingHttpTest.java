package com.example.inventory.sync;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import com.example.inventory.AbstractIntegrationTest;
import com.example.inventory.auth.HttpTestClient;
import com.example.inventory.auth.JwtService;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GET /sync/changes — paging")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "app.sync.page-size=2")
class SyncPagingHttpTest extends AbstractIntegrationTest {

    private static final int PRODUCT_COUNT = 7;

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    private static UUID tenantId;
    private static UUID userId;
    private static List<UUID> productIds;
    private static boolean seeded;

    @BeforeEach
    void seed() throws SQLException {
        if (seeded) {
            return;
        }
        tenantId = newTenantId();
        userId = UUID.randomUUID();
        productIds = new ArrayList<>();
        String tag = tenantId.toString().substring(0, 8);

        try (Connection conn = owner(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO tenants (id, slug, name) VALUES ('%s', 'page-%s', 'Paged')"
                    .formatted(tenantId, tag));
            stmt.execute("""
                    INSERT INTO users (id, tenant_id, email, full_name, role, status)
                    VALUES ('%s', '%s', 'owner-%s@example.test', 'Pa Owner', 'owner', 'active')
                    """.formatted(userId, tenantId, tag));

            for (int i = 0; i < PRODUCT_COUNT; i++) {
                UUID id = UUID.randomUUID();
                productIds.add(id);
                stmt.execute("""
                        INSERT INTO products (id, tenant_id, sku, name, cost_price, selling_price)
                        VALUES ('%s', '%s', 'PAGE-%d-%s', 'Paged Product %d', 1.00, 2.00)
                        """.formatted(id, tenantId, i, tag, i));
            }
        }
        seeded = true;
    }

    private static Connection owner() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private HttpTestClient http() {
        return new HttpTestClient(port);
    }

    private String token() {
        return jwtService.issueAccessToken(tenantId, userId, "owner").value();
    }

    private JsonNode sync(String since) {
        String path = since == null ? "/sync/changes" : "/sync/changes?since=" + since;
        HttpTestClient.Response response = http().get(path, token());
        assertThat(response.status()).as("%s -> %s", path, response.body()).isEqualTo(200);
        return response.json();
    }

    @Test
    @DisplayName("a full snapshot spans several pages and delivers each row exactly once")
    void pagingDeliversEveryRowExactlyOnce() {
        List<String> delivered = new ArrayList<>();
        int pages = 0;

        String since = null;
        boolean more = true;
        while (more) {
            JsonNode body = sync(since);
            for (JsonNode row : body.get("products")) {
                delivered.add(row.get("id").asString());
            }
            since = body.get("syncToken").asString();
            more = body.get("hasMore").asBoolean();
            pages++;

            assertThat(pages)
                    .as("paging did not terminate — the watermark is not advancing, "
                            + "which is the infinite-loop failure a timestamp cursor has")
                    .isLessThan(50);
        }

        assertThat(pages)
                .as("%d products at a page size of 2 must take several calls", PRODUCT_COUNT)
                .isGreaterThan(3);

        assertThat(delivered)
                .as("a row delivered on two consecutive pages means the cursor "
                        + "ranges overlap instead of tiling: %s", delivered)
                .doesNotHaveDuplicates();

        Set<String> expected = new HashSet<>();
        productIds.forEach(id -> expected.add(id.toString()));
        assertThat(new HashSet<>(delivered))
                .as("and every product must arrive; a page boundary that skipped a "
                        + "row would leave one product permanently stale on the device")
                .containsAll(expected);
    }

    @Test
    @DisplayName("hasMore is true until the backlog drains, then false")
    void hasMoreReportsTheBacklogHonestly() {
        JsonNode first = sync(null);

        assertThat(first.get("hasMore").asBoolean())
                .as("%d products at a page size of 2 cannot fit in one page", PRODUCT_COUNT)
                .isTrue();
        assertThat(first.get("products").size()).isLessThanOrEqualTo(2);

        String since = first.get("syncToken").asString();
        JsonNode page = sync(since);
        while (page.get("hasMore").asBoolean()) {
            since = page.get("syncToken").asString();
            page = sync(since);
        }
        since = page.get("syncToken").asString();

        JsonNode drained = sync(since);
        assertThat(drained.get("hasMore").asBoolean()).isFalse();
        assertThat(drained.get("products")).isEmpty();
    }

    @Test
    @DisplayName("a mid-page watermark resumes exactly where it stopped")
    void aMidPageWatermarkResumes() {
        JsonNode first = sync(null);
        assertThat(first.get("hasMore").asBoolean()).isTrue();

        List<String> firstPage = new ArrayList<>();
        for (JsonNode row : first.get("products")) {
            firstPage.add(row.get("id").asString());
        }
        assertThat(firstPage).isNotEmpty();

        JsonNode second = sync(first.get("syncToken").asString());
        List<String> secondPage = new ArrayList<>();
        for (JsonNode row : second.get("products")) {
            secondPage.add(row.get("id").asString());
        }

        assertThat(secondPage)
                .as("the second page must not repeat the first page's last row")
                .doesNotContainAnyElementsOf(firstPage);
        assertThat(secondPage).isNotEmpty();
    }
}
