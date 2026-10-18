package com.example.inventory.auth;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.example.inventory.AbstractIntegrationTest;
import com.example.inventory.auth.AuthDtos.AuthTokens;
import com.example.inventory.auth.AuthDtos.TenantRegistrationRequest;

import static com.example.inventory.auth.HttpTestClient.json;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Invited users and temporary passwords")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InvitePasswordFlowTest extends AbstractIntegrationTest {

    private static final String NEW_PASSWORD = "a-brand-new-password";

    @LocalServerPort
    private int port;

    @Autowired
    private TenantRegistrationService registrationService;

    private HttpTestClient http() {
        return new HttpTestClient(port);
    }

    private record Owner(String slug, String email, String password, AuthTokens tokens) {
    }

    private record Invitee(String slug, String email, String temporaryPassword) {
    }

    private Owner register() {
        String slug = "inv-" + UUID.randomUUID().toString().substring(0, 8);
        String email = slug + "@example.test";
        String password = "correct-horse-battery";
        AuthTokens tokens = registrationService.register(new TenantRegistrationRequest(
                "Invite Trading", slug, email, password, "Ada Owner", "ZAR", "UTC"));
        return new Owner(slug, email, password, tokens);
    }

    private Invitee invite(Owner owner) {
        String email = "invitee-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        var response = http().postWithToken("/users",
                json("email", email, "fullName", "Ivy Invitee", "role", "clerk"),
                owner.tokens().accessToken());
        assertThat(response.status()).isEqualTo(201);
        return new Invitee(owner.slug(), email, response.at("/temporaryPassword"));
    }

    @Test
    @DisplayName("invite returns a 12-character temporary password once, uncached")
    void inviteReturnsTemporaryPassword() {
        Owner owner = register();
        String email = "ivy-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";

        var response = http().postWithToken("/users",
                json("email", email, "fullName", "Ivy Invitee", "role", "clerk"),
                owner.tokens().accessToken());

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.at("/temporaryPassword")).hasSize(12);
        assertThat(response.at("/user/status")).isEqualTo("invited");
        assertThat(response.headers().first("Cache-Control")).contains("no-store");

        var listing = http().get("/users", owner.tokens().accessToken());
        assertThat(listing.body())
                .as("the temporary password is never readable again")
                .doesNotContain(response.at("/temporaryPassword"));
    }

    @Test
    @DisplayName("an invited user can sign in and is flagged; a wrong password still fails")
    void invitedUserCanSignInAndIsFlagged() {
        Invitee invitee = invite(register());

        var ok = http().post("/auth/login", json(
                "email", invitee.email(), "password", invitee.temporaryPassword(),
                "tenantSlug", invitee.slug()));
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.at("/mustChangePassword")).isEqualTo("true");

        var wrong = http().post("/auth/login", json(
                "email", invitee.email(), "password", "not-the-temporary-one",
                "tenantSlug", invitee.slug()));
        assertThat(wrong.status()).isEqualTo(401);
    }

    @Test
    @DisplayName("an owner's login is not flagged")
    void ownerIsNotFlagged() {
        Owner owner = register();

        var response = http().post("/auth/login", json(
                "email", owner.email(), "password", owner.password(), "tenantSlug", owner.slug()));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.at("/mustChangePassword")).isEqualTo("false");
    }

    @Test
    @DisplayName("a flagged token can change the password or sign out, and nothing else")
    void flaggedTokenIsRestricted() {
        Invitee invitee = invite(register());
        var login = http().post("/auth/login", json(
                "email", invitee.email(), "password", invitee.temporaryPassword(),
                "tenantSlug", invitee.slug()));
        String access = login.at("/accessToken");

        assertThat(http().get("/me", access).status()).isEqualTo(403);
        assertThat(http().get("/products", access).status())
                .as("a clerk may normally read products; the flag must override that")
                .isEqualTo(403);
        assertThat(http().postWithToken("/auth/logout", "{}", access).status()).isEqualTo(204);
    }

    @Test
    @DisplayName("a flagged user cannot refresh into an unrestricted token")
    void refreshIsRefusedWhileFlagged() {
        Invitee invitee = invite(register());
        var login = http().post("/auth/login", json(
                "email", invitee.email(), "password", invitee.temporaryPassword(),
                "tenantSlug", invitee.slug()));

        var refresh = http().post("/auth/refresh", json("refreshToken", login.at("/refreshToken")));

        assertThat(refresh.status()).isEqualTo(401);
    }

    @Test
    @DisplayName("changing the password activates the user and kills the temporary one")
    void changePasswordCompletesTheInvitation() {
        Owner owner = register();
        Invitee invitee = invite(owner);
        var login = http().post("/auth/login", json(
                "email", invitee.email(), "password", invitee.temporaryPassword(),
                "tenantSlug", invitee.slug()));
        String access = login.at("/accessToken");
        String oldRefresh = login.at("/refreshToken");

        assertThat(http().postWithToken("/auth/change-password",
                json("currentPassword", "wrong-current-password", "newPassword", NEW_PASSWORD),
                access).status()).as("wrong current password").isEqualTo(400);
        assertThat(http().postWithToken("/auth/change-password",
                json("currentPassword", invitee.temporaryPassword(),
                        "newPassword", invitee.temporaryPassword()),
                access).status()).as("new equals the temporary one").isEqualTo(400);

        var changed = http().postWithToken("/auth/change-password",
                json("currentPassword", invitee.temporaryPassword(), "newPassword", NEW_PASSWORD),
                access);
        assertThat(changed.status()).isEqualTo(200);
        assertThat(changed.at("/mustChangePassword")).isEqualTo("false");
        assertThat(changed.at("/user/role")).isEqualTo("clerk");

        assertThat(http().get("/me", changed.at("/accessToken")).status())
                .as("the new token is unrestricted").isEqualTo(200);
        assertThat(http().post("/auth/refresh", json("refreshToken", oldRefresh)).status())
                .as("every earlier refresh token is revoked").isEqualTo(401);

        assertThat(http().post("/auth/login", json(
                "email", invitee.email(), "password", invitee.temporaryPassword(),
                "tenantSlug", invitee.slug())).status())
                .as("the temporary password no longer works").isEqualTo(401);

        var again = http().post("/auth/login", json(
                "email", invitee.email(), "password", NEW_PASSWORD, "tenantSlug", invitee.slug()));
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.at("/mustChangePassword")).isEqualTo("false");

        assertThat(http().get("/users?status=active", owner.tokens().accessToken()).body())
                .as("invited became active").contains(invitee.email());
    }
}