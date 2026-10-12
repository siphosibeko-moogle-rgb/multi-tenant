package com.example.inventory.web;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import com.example.inventory.AbstractIntegrationTest;
import com.example.inventory.auth.HttpTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Auth rate limiting")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "app.auth.rate-limit.registrations-per-hour=3",
        "app.auth.rate-limit.login-attempts-per-minute=3"
})
class AuthRateLimiterTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private AuthRateLimiter rateLimiter;

    private String registrationPayload() {
        String slug = "rl-" + UUID.randomUUID().toString().substring(0, 8);
        return """
                {
                  "businessName": "Rate Limited Ltd",
                  "slug": "%s",
                  "ownerEmail": "%s@example.test",
                  "ownerPassword": "correct-horse-battery",
                  "ownerName": "Ada Owner"
                }
                """.formatted(slug, slug);
    }

    private static String loginPayload() {
        return """
                {"email":"nobody-%s@example.test","password":"wrong-password-here"}
                """.formatted(UUID.randomUUID());
    }

    private HttpTestClient client() {
        return new HttpTestClient(port);
    }

    @Test
    @DisplayName("registration is refused with 429 once the hourly allowance is spent")
    void registrationIsLimited() {
        var http = client();

        for (int i = 1; i <= 3; i++) {
            assertThat(http.post("/auth/register-tenant", registrationPayload()).status())
                    .as("registration %d of 3 must be allowed", i)
                    .isEqualTo(201);
        }

        var refused = http.post("/auth/register-tenant", registrationPayload());

        assertThat(refused.status())
                .as("the fourth registration from this address must be refused")
                .isEqualTo(429);
        assertThat(refused.headers().first("Retry-After"))
                .as("a client that cannot tell when to retry will simply hammer the endpoint")
                .isNotNull();
        assertThat(refused.body())
                .as("RFC 9457, like every other error")
                .contains("\"status\":429");
    }

    @Test
    @DisplayName("login is limited too, and a refused attempt is not a failed login")
    void loginIsLimited() {
        var http = client();

        for (int i = 1; i <= 3; i++) {
            assertThat(http.post("/auth/login", loginPayload()).status())
                    .as("attempt %d is a normal failed login", i)
                    .isEqualTo(401);
        }

        assertThat(http.post("/auth/login", loginPayload()).status())
                .as("credential stuffing must hit a wall rather than an unbounded stream of 401s")
                .isEqualTo(429);
    }

    @Test
    @DisplayName("the limiter counts registrations and logins separately")
    void thebucketsAreIndependent() {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setRemoteAddr("203.0.113." + (int) (Math.random() * 250 + 1));

        for (int i = 0; i < 3; i++) {
            rateLimiter.checkRegistration(request);
        }

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> rateLimiter.checkRegistration(request))
                .isInstanceOf(RateLimitedException.class);

        rateLimiter.checkLogin(request);
    }
}
