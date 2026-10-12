package com.example.inventory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

@DisplayName("The API contract")
class ApiContractTest {

    private static final Path CONTRACT = Path.of("..", "docs", "openapi.yaml");

    @Test
    @DisplayName("exists and parses as YAML")
    void contractParsesAsYaml() throws Exception {
        assertThat(CONTRACT)
                .as("docs/openapi.yaml is the source of truth and must be present")
                .exists();

        String content = Files.readString(CONTRACT);
        try {
            Object parsed = new Yaml().load(content);
            assertThat(parsed)
                    .as("the contract must be a YAML mapping, not a scalar or a list")
                    .isInstanceOf(Map.class);
        } catch (Exception e) {
            fail("""
                    docs/openapi.yaml does not parse as YAML.

                    This is exactly the failure that went unnoticed from the first commit \
                    through M0 and M1: the file was flush-left, so every level of nesting \
                    was gone while the content still read correctly to a human.

                    Do not reconstruct the indentation by inference — a plausible-but-wrong \
                    structure in the contract of record is worse than an obviously broken \
                    one. Restore a known-good copy.

                    Parser said: %s""".formatted(e.getMessage()));
        }
    }

    @Test
    @DisplayName("is a valid OpenAPI 3.1 document")
    void contractIsValidOpenApi31() {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(false);

        SwaggerParseResult result =
                new OpenAPIV3Parser().readLocation(CONTRACT.toString(), null, options);

        List<String> messages = result.getMessages() == null ? List.of() : result.getMessages();

        assertThat(result.getOpenAPI())
                .as("the contract must be readable as an OpenAPI document. Parser messages: %s",
                        messages)
                .isNotNull();

        assertThat(messages)
                .as("the contract must have no validation errors — including dangling $refs")
                .isEmpty();

        assertThat(result.getOpenAPI().getOpenapi())
                .as("this project targets OpenAPI 3.1")
                .startsWith("3.1");

        assertThat(result.getOpenAPI().getPaths())
                .as("a contract with no paths is not a contract")
                .isNotEmpty();
    }

    @Test
    @DisplayName("still declares the auth endpoints M1 implements")
    void contractCoversTheAuthEndpoints() {
        SwaggerParseResult result = new OpenAPIV3Parser().readLocation(
                CONTRACT.toString(), null, new ParseOptions());

        assertThat(result.getOpenAPI())
                .as("covered by contractIsValidOpenApi31; skipping detail if unreadable")
                .isNotNull();

        assertThat(result.getOpenAPI().getPaths().keySet())
                .as("M1 implements these, so the contract must still describe them")
                .contains("/auth/register-tenant", "/auth/login", "/auth/refresh",
                        "/auth/logout", "/me", "/users");
    }
}
