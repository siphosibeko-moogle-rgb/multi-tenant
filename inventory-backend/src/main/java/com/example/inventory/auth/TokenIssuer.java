package com.example.inventory.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.example.inventory.auth.AuthDtos.AuthTokens;
import com.example.inventory.auth.AuthDtos.CurrentUser;
import com.example.inventory.auth.JwtService.IssuedToken;

@Service
public class TokenIssuer {

    private final JwtService jwtService;
    private final JdbcTemplate appJdbc;

    public TokenIssuer(JwtService jwtService, @Qualifier("appDataSource") DataSource appDataSource) {
        this.jwtService = jwtService;
        this.appJdbc = new JdbcTemplate(appDataSource);
    }

    public AuthTokens issueFor(UUID tenantId, UUID userId, String role,
                               CurrentUser user, String deviceLabel) {
        IssuedToken access = jwtService.issueAccessToken(tenantId, userId, role);

        UUID jti = UUID.randomUUID();
        IssuedToken refresh = jwtService.issueRefreshToken(tenantId, userId, jti);

        appJdbc.update("""
                INSERT INTO refresh_tokens (id, tenant_id, user_id, token_hash, device_label, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                jti,
                tenantId,
                userId,
                sha256(refresh.value()),
                deviceLabel,
                java.sql.Timestamp.from(Instant.now().plus(refresh.ttl())));

        return AuthTokens.of(access.value(), refresh.value(), access.ttl().toSeconds(), user);
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is required by the JDK and must be present", e);
        }
    }
}
