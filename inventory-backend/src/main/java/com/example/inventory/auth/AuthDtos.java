package com.example.inventory.auth;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record TenantRegistrationRequest(
            @NotBlank @Size(max = 200) String businessName,
            @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,62}$",
                    message = "must be lowercase letters, digits and hyphens")
            String slug,
            @NotBlank @Email @Size(max = 320) String ownerEmail,
            @NotBlank @Size(min = 8, max = 200) String ownerPassword,
            @NotBlank @Size(max = 200) String ownerName,
            @Size(min = 3, max = 3) String currencyCode,
            String timezone) {

        public String currencyCodeOrDefault() {
            return currencyCode == null || currencyCode.isBlank() ? "ZAR" : currencyCode;
        }

        public String timezoneOrDefault() {
            return timezone == null || timezone.isBlank() ? "UTC" : timezone;
        }

        @AssertTrue(message = "must be an IANA time zone, for example Africa/Johannesburg")
        public boolean isTimezoneValid() {
            return timezone == null || timezone.isBlank()
                    || java.time.ZoneId.getAvailableZoneIds().contains(timezone);
        }
    }

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8) String password,
            String tenantSlug,
            String deviceLabel) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record TenantSummary(UUID id, String slug, String name) {
    }

    public record CurrentUser(
            UUID id,
            String email,
            String fullName,
            String role,
            TenantSummary tenant,
            String currencyCode,
            String timezone,
            UUID defaultLocationId) {
    }

    public record AuthTokens(
            String accessToken,
            String refreshToken,
            long expiresIn,
            String tokenType,
            CurrentUser user) {

        public static AuthTokens of(String access, String refresh, long expiresIn, CurrentUser user) {
            return new AuthTokens(access, refresh, expiresIn, "Bearer", user);
        }
    }

    public record TenantChoices(List<TenantSummary> tenants) {
    }
}
