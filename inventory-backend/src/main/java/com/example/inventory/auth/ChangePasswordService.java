package com.example.inventory.auth;

import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.inventory.auth.AuthDtos.AuthTokens;
import com.example.inventory.auth.AuthDtos.ChangePasswordRequest;
import com.example.inventory.auth.AuthDtos.CurrentUser;
import com.example.inventory.web.BadRequestException;
import com.example.inventory.web.UnauthorizedException;

@Service
public class ChangePasswordService {

    private final JdbcTemplate appJdbc;
    private final TransactionTemplate transactions;
    private final PasswordEncoder passwordEncoder;
    private final TokenIssuer tokenIssuer;
    private final CurrentUserService currentUserService;
    private final RefreshTokenService refreshTokenService;

    public ChangePasswordService(@Qualifier("appDataSource") DataSource appDataSource,
                                 TransactionTemplate transactions,
                                 PasswordEncoder passwordEncoder,
                                 TokenIssuer tokenIssuer,
                                 CurrentUserService currentUserService,
                                 RefreshTokenService refreshTokenService) {
        this.appJdbc = new JdbcTemplate(appDataSource);
        this.transactions = transactions;
        this.passwordEncoder = passwordEncoder;
        this.tokenIssuer = tokenIssuer;
        this.currentUserService = currentUserService;
        this.refreshTokenService = refreshTokenService;
    }

    private record Stored(String passwordHash, String role) {
    }

    /**
     * One transaction: verify, store the new hash, clear the flag, activate an invited
     * user, revoke every refresh token, and mint a fresh pair so the app need not sign in again.
     */
    public AuthTokens change(UUID tenantId, UUID userId, ChangePasswordRequest request) {
        return transactions.execute(status -> {
            Stored stored = appJdbc.query("""
                    SELECT password_hash, role::text AS role
                    FROM users
                    WHERE id = ? AND tenant_id = ? AND deleted_at IS NULL
                      AND status IN ('active', 'invited')
                    FOR UPDATE
                    """,
                    (rs, i) -> new Stored(rs.getString("password_hash"), rs.getString("role")),
                    userId, tenantId)
                    .stream().findFirst()
                    .orElseThrow(() -> new UnauthorizedException("Not authenticated"));

            // 400, not 401: the app treats 401 as "session expired" and would try to refresh.
            if (stored.passwordHash() == null
                    || !passwordEncoder.matches(request.currentPassword(), stored.passwordHash())) {
                throw new BadRequestException("Your current password is not correct");
            }
            if (passwordEncoder.matches(request.newPassword(), stored.passwordHash())) {
                throw new BadRequestException("Choose a password different from your current one");
            }

            appJdbc.update("""
                    UPDATE users
                    SET password_hash = ?,
                        must_change_password = false,
                        status = CASE WHEN status = 'invited' THEN 'active'::user_status ELSE status END
                    WHERE id = ?
                    """, passwordEncoder.encode(request.newPassword()), userId);

            refreshTokenService.logoutEverySession(tenantId, userId);

            CurrentUser user = currentUserService.load(tenantId, userId)
                    .orElseThrow(() -> new UnauthorizedException("Not authenticated"));
            return tokenIssuer.issueFor(tenantId, userId, stored.role(), user, null);
        });
    }
}