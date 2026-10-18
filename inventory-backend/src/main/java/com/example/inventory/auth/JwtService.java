package com.example.inventory.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.example.inventory.auth.AuthProperties.TokenTtl;

@Service
public class JwtService {

    static final String TOKEN_TYPE_CLAIM = "typ";
    static final String REFRESH_TOKEN_TYPE = "refresh";
    static final String TENANT_CLAIM = "tid";
    static final String ROLE_CLAIM = "role";
    /** Present and true only on access tokens issued while must_change_password is set. */
    static final String PASSWORD_CHANGE_CLAIM = "pwc";

    private final JwtEncoder encoder;
    private final TokenTtl ttl;
    private final String issuer;

    public JwtService(JwtEncoder encoder, AuthProperties properties) {
        this.encoder = encoder;
        this.ttl = properties.ttl();
        this.issuer = properties.issuer();
    }

    public IssuedToken issueAccessToken(UUID tenantId, UUID userId, String role) {
        return issueAccessToken(tenantId, userId, role, false);
    }

    public IssuedToken issueAccessToken(UUID tenantId, UUID userId, String role,
                                        boolean passwordChangeRequired) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(now)
                .expiresAt(now.plus(ttl.access()))
                .subject(userId.toString())
                .id(UUID.randomUUID().toString())
                .claim(TENANT_CLAIM, tenantId.toString())
                .claim(ROLE_CLAIM, role);
        if (passwordChangeRequired) {
            claims.claim(PASSWORD_CHANGE_CLAIM, true);
        }
        return new IssuedToken(encode(claims.build()), ttl.access());
    }

    public IssuedToken issueRefreshToken(UUID tenantId, UUID userId, UUID jti) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(now)
                .expiresAt(now.plus(ttl.refresh()))
                .subject(userId.toString())
                .id(jti.toString())
                .claim(TENANT_CLAIM, tenantId.toString())
                .claim(TOKEN_TYPE_CLAIM, REFRESH_TOKEN_TYPE)
                .build();
        return new IssuedToken(encode(claims), ttl.refresh());
    }

    private String encode(JwtClaimsSet claims) {
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(AuthProperties.SIGNING_ALGORITHM).build(), claims)).getTokenValue();
    }

    public record IssuedToken(String value, Duration ttl) {
    }
}