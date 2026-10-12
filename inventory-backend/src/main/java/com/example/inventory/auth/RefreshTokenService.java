package com.example.inventory.auth;

import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.inventory.auth.AuthDtos.AuthTokens;
import com.example.inventory.tenancy.TenantContext;
import com.example.inventory.web.UnauthorizedException;

@Service
public class RefreshTokenService {

    private final JwtDecoder jwtDecoder;
    private final JdbcTemplate appJdbc;
    private final TransactionTemplate transactions;
    private final TokenIssuer tokenIssuer;
    private final CurrentUserService currentUserService;

    public RefreshTokenService(@Qualifier("refreshTokenDecoder") JwtDecoder jwtDecoder,
                               @Qualifier("appDataSource") DataSource appDataSource,
                               TransactionTemplate transactions,
                               TokenIssuer tokenIssuer,
                               CurrentUserService currentUserService) {
        this.jwtDecoder = jwtDecoder;
        this.appJdbc = new JdbcTemplate(appDataSource);
        this.transactions = transactions;
        this.tokenIssuer = tokenIssuer;
        this.currentUserService = currentUserService;
    }

    public AuthTokens refresh(String presentedToken, String deviceLabel) {
        Jwt jwt = verifyRefreshToken(presentedToken);

        UUID tenantId = UUID.fromString(jwt.getClaimAsString(JwtService.TENANT_CLAIM));
        UUID userId = UUID.fromString(jwt.getSubject());
        UUID jti = UUID.fromString(jwt.getId());

        TenantContext.bind(new TenantContext.TenantIdentity(tenantId, userId, null));
        Outcome outcome;
        try {
            outcome = transactions.execute(status ->
                    rotate(presentedToken, tenantId, userId, jti, deviceLabel));
        } finally {
            TenantContext.clear();
        }

        return switch (outcome) {
            case Outcome.Rotated rotated -> rotated.tokens();
            case Outcome.Rejected rejected -> throw new UnauthorizedException(rejected.reason());
        };
    }

    private sealed interface Outcome {
        record Rotated(AuthTokens tokens) implements Outcome {
        }

        record Rejected(String reason) implements Outcome {
        }
    }

    private Jwt verifyRefreshToken(String presentedToken) {
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(presentedToken);
        } catch (JwtException e) {
            throw new UnauthorizedException("Invalid refresh token");
        }
        if (!JwtService.REFRESH_TOKEN_TYPE.equals(
                jwt.getClaimAsString(JwtService.TOKEN_TYPE_CLAIM))) {
            throw new UnauthorizedException("Invalid refresh token");
        }
        if (jwt.getId() == null || jwt.getSubject() == null
                || jwt.getClaimAsString(JwtService.TENANT_CLAIM) == null) {
            throw new UnauthorizedException("Invalid refresh token");
        }
        return jwt;
    }

    private Outcome rotate(String presentedToken, UUID tenantId, UUID userId,
                           UUID jti, String deviceLabel) {
        String presentedHash = TokenIssuer.sha256(presentedToken);

        var rows = appJdbc.queryForList("""
                SELECT id, revoked_at, expires_at < now() AS expired
                FROM refresh_tokens
                WHERE id = ? AND token_hash = ? AND user_id = ?
                """, jti, presentedHash, userId);

        if (rows.isEmpty()) {
            return new Outcome.Rejected("Invalid refresh token");
        }

        Map<String, Object> row = rows.get(0);
        if (row.get("revoked_at") != null) {
            revokeEveryTokenFor(userId);
            return new Outcome.Rejected("Refresh token has already been used");
        }
        if (Boolean.TRUE.equals(row.get("expired"))) {
            return new Outcome.Rejected("Refresh token has expired");
        }

        appJdbc.update("UPDATE refresh_tokens SET revoked_at = now() WHERE id = ?", jti);

        var user = currentUserService.load(tenantId, userId).orElse(null);
        if (user == null) {
            return new Outcome.Rejected("Invalid refresh token");
        }

        return new Outcome.Rotated(
                tokenIssuer.issueFor(tenantId, userId, user.role(), user, deviceLabel));
    }

    public void logoutEverySession(UUID tenantId, UUID userId) {
        revokeEveryTokenFor(userId);
    }

    private void revokeEveryTokenFor(UUID userId) {
        appJdbc.update(
                "UPDATE refresh_tokens SET revoked_at = now() "
                        + "WHERE user_id = ? AND revoked_at IS NULL", userId);
    }

    public void logout(String presentedToken) {
        Jwt jwt;
        try {
            jwt = verifyRefreshToken(presentedToken);
        } catch (UnauthorizedException e) {
            return;
        }

        UUID tenantId = UUID.fromString(jwt.getClaimAsString(JwtService.TENANT_CLAIM));
        UUID userId = UUID.fromString(jwt.getSubject());
        UUID jti = UUID.fromString(jwt.getId());

        TenantContext.bind(new TenantContext.TenantIdentity(tenantId, userId, null));
        try {
            appJdbc.update(
                    "UPDATE refresh_tokens SET revoked_at = now() "
                            + "WHERE id = ? AND user_id = ? AND revoked_at IS NULL", jti, userId);
        } finally {
            TenantContext.clear();
        }
    }
}
