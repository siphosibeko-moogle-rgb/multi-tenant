package com.example.inventory.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;

@ConfigurationProperties("app.auth")
public record AuthProperties(
        String signingKey,
        @DefaultValue("inventory-backend") String issuer,
        @DefaultValue TokenTtl ttl,
        @DefaultValue RateLimit rateLimit) {

    public static final MacAlgorithm SIGNING_ALGORITHM = MacAlgorithm.HS256;

    public static final int MINIMUM_KEY_BYTES = 32;

    public record TokenTtl(
            @DefaultValue("15m") Duration access,
            @DefaultValue("30d") Duration refresh) {
    }

    public record RateLimit(
            @DefaultValue("5") int registrationsPerHour,
            @DefaultValue("10") int loginAttemptsPerMinute) {
    }
}
