package com.example.inventory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.example.inventory.auth.HttpTestClient;
import com.example.inventory.auth.JwtService;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Responses satisfy the contract's required fields")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ResponseRequiredFieldsHttpTest extends AbstractIntegrationTest {

    private static final Path CONTRACT = Path.of("..", "docs", "openapi.yaml");

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    private static OpenAPI contract;

    private static UUID tenantId;
    private static UUID userId;
    private static UUID locationId;
    private static UUID categoryId;
    private static UUID productId;
    private static boolean seeded;

    @BeforeEach
    void seed() throws SQLException {
        if (contract == null) {
            ParseOptions options = new ParseOptions();
            options.setResolve(true);
            contract = new OpenAPIV3Parser().read(CONTRACT.toString(), null, options);
            assertThat(contract).as("docs/openapi.yaml must parse").isNotNull();
        }
        if (seeded) {
            return;
        }
        tenantId = newTenantId();
        userId = UUID.randomUUID();
        locationId = UUID.randomUUID();
        categoryId = UUID.randomUUID();
        productId = UUID.randomUUID();
        String tag = tenantId.toString().substring(0, 8);

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {

            stmt.execute("INSERT INTO tenants (id, slug, name) VALUES ('%s', 'req-%s', 'Required Shop')"
                    .formatted(tenantId, tag));
            stmt.execute("""
                    INSERT INTO users (id, tenant_id, email, full_name, role, status)
                    VALUES ('%s', '%s', 'owner-%s@example.test', 'Olu Owner', 'owner', 'active')
                    """.formatted(userId, tenantId, tag));
            stmt.execute("INSERT INTO locations (id, tenant_id, name, is_default) "
                    + "VALUES ('%s', '%s', 'Main', true)".formatted(locationId, tenantId));
            stmt.execute("INSERT INTO categories (id, tenant_id, name) "
                    + "VALUES ('%s', '%s', 'Bakery')".formatted(categoryId, tenantId));
            stmt.execute("""
                    INSERT INTO products (id, tenant_id, sku, name, category_id,
                                          cost_price, selling_price, tax_rate, reorder_point)
                    VALUES ('%s', '%s', 'SKU-REQ-1', 'Sourdough', '%s', 7.25, 12.50, 0.15, 4)
                    """.formatted(productId, tenantId, categoryId));
        }
        seeded = true;
    }

    private HttpTestClient http() {
        return new HttpTestClient(port);
    }

    private String ownerToken() {
        return jwtService.issueAccessToken(tenantId, userId, "owner").value();
    }

    private static Schema<?> schema(String name) {
        Schema<?> schema = contract.getComponents().getSchemas().get(name);
        assertThat(schema).as("schema %s must exist in the contract", name).isNotNull();
        return schema;
    }

    private static List<String> requiredOf(String schemaName) {
        List<String> required = new ArrayList<>();
        collectRequired(schema(schemaName), required);
        assertThat(required)
                .as("%s declares no required fields, so this assertion would check nothing",
                        schemaName)
                .isNotEmpty();
        return required;
    }

    private static void collectRequired(Schema<?> schema, List<String> into) {
        if (schema == null) {
            return;
        }
        if (schema.get$ref() != null) {
            String name = schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1);
            collectRequired(contract.getComponents().getSchemas().get(name), into);
        }
        if (schema.getRequired() != null) {
            into.addAll(schema.getRequired());
        }
        if (schema.getAllOf() != null) {
            for (Schema<?> part : schema.getAllOf()) {
                collectRequired(part, into);
            }
        }
    }

    private static List<String> requiredOfArrayProperty(String schemaName, String property) {
        Schema<?> found = findProperty(schema(schemaName), property);
        assertThat(found).as("%s must declare a property %s", schemaName, property).isNotNull();
        Schema<?> items = found.getItems();
        assertThat(items).as("%s.%s must be an array", schemaName, property).isNotNull();

        List<String> required = new ArrayList<>();
        collectRequired(items, required);
        assertThat(required)
                .as("%s.%s items declare no required fields", schemaName, property)
                .isNotEmpty();
        return required;
    }

    private static Schema<?> findProperty(Schema<?> schema, String property) {
        if (schema == null) {
            return null;
        }
        if (schema.getProperties() != null && schema.getProperties().containsKey(property)) {
            return (Schema<?>) schema.getProperties().get(property);
        }
        if (schema.getAllOf() != null) {
            for (Schema<?> part : schema.getAllOf()) {
                Schema<?> found = findProperty(part, property);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void satisfies(JsonNode object, String label, List<String> required) {
        assertThat(object.isObject())
                .as("expected a JSON object to check against %s, got: %s", label, object)
                .isTrue();

        List<String> missing = new ArrayList<>();
        List<String> nulls = new ArrayList<>();
        for (String field : required) {
            JsonNode value = object.get(field);
            if (value == null) {
                missing.add(field);
            } else if (value.isNull()) {
                nulls.add(field);
            }
        }

        assertThat(missing)
                .as("%s: the contract requires these fields and the response omits them "
                        + "entirely. A generated client cannot construct the model at all. "
                        + "Body: %s", label, object)
                .isEmpty();
        assertThat(nulls)
                .as("%s: the contract requires these fields and the response sends them as "
                        + "null. A generated client types them non-nullable and fails to "
                        + "deserialize. Body: %s", label, object)
                .isEmpty();
    }

    private static void satisfies(JsonNode object, String schemaName) {
        satisfies(object, schemaName, requiredOf(schemaName));
    }

    private static void eachSatisfies(JsonNode array, String label, List<String> required) {
        assertThat(array).as("expected an array of %s but the field was absent", label).isNotNull();
        assertThat(array.isArray())
                .as("expected an array of %s, got: %s", label, array).isTrue();
        assertThat(array.size())
                .as("the fixture must produce at least one %s — an empty list satisfies "
                        + "every field assertion without checking anything", label)
                .isGreaterThan(0);
        for (JsonNode element : array) {
            satisfies(element, label, required);
        }
    }

    private static void eachSatisfies(JsonNode array, String schemaName) {
        eachSatisfies(array, schemaName, requiredOf(schemaName));
    }

    @Test
    @DisplayName("GET /me satisfies CurrentUser")
    void currentUser() {
        var response = http().get("/me", ownerToken());
        assertThat(response.status()).isEqualTo(200);
        satisfies(response.json(), "CurrentUser");

        assertThat(requiredOf("CurrentUser")).doesNotContain("defaultLocationId");
    }

    @Test
    @DisplayName("GET /products satisfies Product for every row")
    void products() {
        var response = http().get("/products", ownerToken());
        assertThat(response.status()).isEqualTo(200);
        eachSatisfies(response.json().get("items"), "Product");
    }

    @Test
    @DisplayName("GET /categories satisfies Category")
    void categories() {
        var response = http().get("/categories", ownerToken());
        assertThat(response.status()).isEqualTo(200);
        eachSatisfies(response.json(), "Category");
    }

    @Test
    @DisplayName("GET /locations satisfies Location")
    void locations() {
        var response = http().get("/locations", ownerToken());
        assertThat(response.status()).isEqualTo(200);
        eachSatisfies(response.json(), "Location");
    }

    @Test
    @DisplayName("GET /users satisfies User")
    void users() {
        var response = http().get("/users", ownerToken());
        assertThat(response.status()).isEqualTo(200);
        eachSatisfies(response.json().get("items"), "User");
    }

    @Test
    @DisplayName("GET /inventory satisfies StockStatus")
    void inventory() {
        http().postWithToken("/inventory/adjustments", """
                {"productId":"%s","quantityDelta":9,"reason":"opening"}
                """.formatted(productId), ownerToken());

        var response = http().get("/inventory", ownerToken());
        assertThat(response.status()).isEqualTo(200);
        eachSatisfies(response.json().get("items"), "StockStatus");
    }

    @Test
    @DisplayName("POST /inventory/adjustments satisfies StockMovement, and names the actor")
    void adjustmentEchoSatisfiesStockMovement() {
        var response = http().postWithToken("/inventory/adjustments", """
                {"productId":"%s","quantityDelta":4,"reason":"opening"}
                """.formatted(productId), ownerToken());
        assertThat(response.status()).isEqualTo(201);

        satisfies(response.json(), "StockMovement");

        assertThat(response.json().get("createdBy").asString())
                .as("an authenticated adjustment must name its actor")
                .isEqualTo(userId.toString());
        assertThat(response.json().get("createdByName").asString()).isEqualTo("Olu Owner");
    }

    @Test
    @DisplayName("GET /inventory/movements satisfies StockMovement")
    void movements() {
        http().postWithToken("/inventory/adjustments", """
                {"productId":"%s","quantityDelta":6,"reason":"opening"}
                """.formatted(productId), ownerToken());

        var response = http().get("/inventory/movements", ownerToken());
        assertThat(response.status()).isEqualTo(200);
        eachSatisfies(response.json().get("items"), "StockMovement");
    }

    @Test
    @DisplayName("POST /sales satisfies SaleDetail, line items included")
    void sale() {
        http().postWithToken("/inventory/adjustments", """
                {"productId":"%s","quantityDelta":20,"reason":"opening"}
                """.formatted(productId), ownerToken());

        var response = http().postWithToken("/sales", """
                {"lines":[{"productId":"%s","quantity":2}]}
                """.formatted(productId), ownerToken());
        assertThat(response.status()).isEqualTo(201);

        satisfies(response.json(), "SaleDetail");
        eachSatisfies(response.json().get("lines"), "SaleDetail.lines[]",
                requiredOfArrayProperty("SaleDetail", "lines"));
    }

    @Test
    @DisplayName("an oversell 409 satisfies InsufficientStockProblem")
    void oversell() {
        var response = http().postWithToken("/sales", """
                {"lines":[{"productId":"%s","quantity":99999}]}
                """.formatted(productId), ownerToken());
        assertThat(response.status()).isEqualTo(409);
        satisfies(response.json(), "InsufficientStockProblem");
    }

    @Test
    @DisplayName("GET /products/{id} satisfies Product, but NOT yet ProductDetail")
    void productDetailIsNotYetImplemented() {
        var response = http().get("/products/" + productId, ownerToken());
        assertThat(response.status()).isEqualTo(200);

        satisfies(response.json(), "Product");

        assertThat(requiredOf("ProductDetail"))
                .as("if stockByLocation stopped being required, this pin is stale")
                .contains("stockByLocation");
        assertThat(response.json().has("stockByLocation"))
                .as("stockByLocation is now implemented — delete this test and assert "
                        + "satisfies(body, \"ProductDetail\") instead")
                .isFalse();
    }

    @Test
    @DisplayName("a 401 satisfies Problem, body and content type")
    void unauthorizedSatisfiesProblem() {
        var response = http().get("/products", null);
        assertThat(response.status()).isEqualTo(401);

        assertThat(response.body())
                .as("a 401 with an empty body cannot be reported to a user at all")
                .isNotBlank();
        satisfies(response.json(), "Problem");

        assertThat(response.headers().first("Content-Type"))
                .as("RFC 9457 bodies are application/problem+json, not application/json")
                .startsWith("application/problem+json");

        assertThat(response.headers().first("WWW-Authenticate"))
                .as("the bearer challenge must survive the added body")
                .isNotNull();

        assertThat(response.json().get("type").asString())
                .isEqualTo("https://api.example.com/problems/unauthenticated");
        assertThat(response.json().get("title").asString()).isEqualTo("Authentication required");
        assertThat(response.json().get("instance").asString()).isEqualTo("/api/v1/products");
    }

    @Test
    @DisplayName("an expired token and an absent one are indistinguishable")
    void unauthorizedIsNotAnOracle() {
        var absent = http().get("/products", null);
        var garbage = http().get("/products", "not-a-jwt-at-all");
        var forged = http().get("/products", jwtService
                .issueAccessToken(tenantId, userId, "owner").value() + "tampered");

        assertThat(absent.status()).isEqualTo(401);
        assertThat(garbage.status()).isEqualTo(401);
        assertThat(forged.status()).isEqualTo(401);

        assertThat(garbage.json().get("detail").asString())
                .isEqualTo(absent.json().get("detail").asString());
        assertThat(forged.json().get("detail").asString())
                .isEqualTo(absent.json().get("detail").asString());
        assertThat(forged.json().get("type").asString())
                .isEqualTo(absent.json().get("type").asString());
    }

    @Test
    @DisplayName("a 403 satisfies Problem")
    void forbiddenSatisfiesProblem() {
        String viewer = jwtService.issueAccessToken(tenantId, UUID.randomUUID(), "viewer").value();

        var response = http().postWithToken("/users", """
                {"email":"new-%s@example.test","fullName":"Nia New","role":"clerk"}
                """.formatted(UUID.randomUUID().toString().substring(0, 8)), viewer);

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.body()).isNotBlank();
        satisfies(response.json(), "Problem");
    }

    private static List<String> requiredOfPathResponse(String path, String pointer) {
        io.swagger.v3.oas.models.PathItem item = contract.getPaths().get(path);
        assertThat(item).as("the contract must declare %s", path).isNotNull();

        Schema<?> schema = item.getGet().getResponses().get("200")
                .getContent().get("application/json").getSchema();
        assertThat(schema).as("%s must declare a 200 application/json schema", path).isNotNull();

        if (pointer != null) {
            Schema<?> property = findProperty(schema, pointer);
            assertThat(property).as("%s must declare %s", path, pointer).isNotNull();
            schema = property.getItems() != null ? property.getItems() : property;
        } else if (schema.getItems() != null) {
            schema = schema.getItems();
        }

        List<String> required = new ArrayList<>();
        collectRequired(schema, required);
        assertThat(required)
                .as("%s (%s) declares no required fields, so this checks nothing", path, pointer)
                .isNotEmpty();
        return required;
    }

    private void sellSomething() {
        http().postWithToken("/inventory/adjustments", """
                {"productId":"%s","quantityDelta":30,"reason":"opening"}
                """.formatted(productId), ownerToken());
        http().postWithToken("/sales", """
                {"lines":[{"productId":"%s","quantity":3}]}
                """.formatted(productId), ownerToken());
    }

    private static String todayIso() {
        return java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
    }

    private static String daysAgoIso(int days) {
        return java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(days).toString();
    }

    @Test
    @DisplayName("GET /reports/dashboard satisfies DashboardSummary, its trend and its topProducts")
    void dashboard() {
        sellSomething();

        var response = http().get("/reports/dashboard", ownerToken());
        assertThat(response.status()).isEqualTo(200);

        satisfies(response.json(), "DashboardSummary");
        eachSatisfies(response.json().get("salesTrend"), "DashboardSummary.salesTrend[]",
                requiredOfArrayProperty("DashboardSummary", "salesTrend"));
        eachSatisfies(response.json().get("topProducts"), "DashboardSummary.topProducts[]",
                requiredOfArrayProperty("DashboardSummary", "topProducts"));
    }

    @Test
    @DisplayName("GET /reports/sales-summary satisfies its inline bucket schema")
    void salesSummary() {
        sellSomething();

        var response = http().get("/reports/sales-summary?from=%s&to=%s"
                .formatted(daysAgoIso(6), todayIso()), ownerToken());
        assertThat(response.status()).isEqualTo(200);

        satisfies(response.json(), "sales-summary body",
                requiredOfPathResponse("/reports/sales-summary", null));
        eachSatisfies(response.json().get("buckets"), "sales-summary bucket",
                requiredOfPathResponse("/reports/sales-summary", "buckets"));
    }

    @Test
    @DisplayName("GET /reports/inventory-valuation satisfies its inline schema")
    void inventoryValuation() {
        sellSomething();

        var response = http().get("/reports/inventory-valuation", ownerToken());
        assertThat(response.status()).isEqualTo(200);

        satisfies(response.json(), "inventory-valuation body",
                requiredOfPathResponse("/reports/inventory-valuation", null));
    }

    @Test
    @DisplayName("GET /reports/top-products satisfies its inline row schema")
    void topProducts() {
        sellSomething();

        var response = http().get("/reports/top-products", ownerToken());
        assertThat(response.status()).isEqualTo(200);

        eachSatisfies(response.json(), "top-products row",
                requiredOfPathResponse("/reports/top-products", null));
    }

    @Test
    @DisplayName("an ordinary error satisfies Problem")
    void problem() {
        var response = http().get("/products/" + UUID.randomUUID(), ownerToken());
        assertThat(response.status()).isEqualTo(404);
        satisfies(response.json(), "Problem");
    }
}
