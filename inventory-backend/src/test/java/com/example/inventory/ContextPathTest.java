package com.example.inventory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Base path")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContextPathTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    private HttpResponse<String> get(String rawPath) {
        try {
            return HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + rawPath))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("request failed: " + rawPath, e);
        }
    }

    @Test
    @DisplayName("an API endpoint is served under /api/v1")
    void endpointsLiveUnderTheVersionedPrefix() {
        assertThat(get("/api/v1/me").statusCode())
                .as("/api/v1/me must be routed — 401 means it exists and rejected an "
                        + "unauthenticated caller, which is the correct answer here")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("the same endpoint is NOT served at the root")
    void endpointsAreNotAlsoAtTheRoot() {
        assertThat(get("/me").statusCode())
                .as("nothing may answer at the root: the contract says /api/v1, and a server "
                        + "answering both is how a client ends up depending on the wrong one")
                .isEqualTo(404);
    }

    @Test
    @DisplayName("actuator health moved under the prefix, and is reachable there")
    void actuatorIsReachableUnderThePrefix() {
        var response = get("/api/v1/actuator/health");

        assertThat(response.statusCode())
                .as("the health endpoint must still answer, and still without a token")
                .isEqualTo(200);
        assertThat(response.body()).contains("UP");
    }

    @Test
    @DisplayName("actuator is no longer at the root, which is the migration hazard")
    void actuatorIsNotAtTheRoot() {
        assertThat(get("/actuator/health").statusCode())
                .as("recorded deliberately: any probe still pointing at /actuator/health needs "
                        + "moving to /api/v1/actuator/health")
                .isEqualTo(404);
    }

    @Test
    @DisplayName("an unauthenticated auth endpoint is reachable under the prefix")
    void authEndpointsAreReachable() {
        assertThat(get("/api/v1/auth/login").statusCode())
                .as("405 means mapped-but-wrong-method; 404 would mean the login route is gone")
                .isEqualTo(405);
    }
}
