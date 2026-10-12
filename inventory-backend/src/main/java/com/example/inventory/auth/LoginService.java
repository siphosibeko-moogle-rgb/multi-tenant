package com.example.inventory.auth;

import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.example.inventory.auth.AuthDtos.AuthTokens;
import com.example.inventory.auth.AuthDtos.CurrentUser;
import com.example.inventory.auth.AuthDtos.LoginRequest;
import com.example.inventory.auth.AuthDtos.TenantSummary;
import com.example.inventory.tenancy.TenantContext;
import com.example.inventory.web.UnauthorizedException;

@Service
public class LoginService {

    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final JdbcTemplate loginJdbc;
    private final PasswordEncoder passwordEncoder;
    private final TokenIssuer tokenIssuer;
    private final CurrentUserService currentUserService;

    public LoginService(@Qualifier("loginDataSource") DataSource loginDataSource,
                        PasswordEncoder passwordEncoder,
                        TokenIssuer tokenIssuer,
                        CurrentUserService currentUserService) {
        this.loginJdbc = new JdbcTemplate(loginDataSource);
        this.passwordEncoder = passwordEncoder;
        this.tokenIssuer = tokenIssuer;
        this.currentUserService = currentUserService;
    }

    private record Candidate(UUID userId, UUID tenantId, String slug, String tenantName,
                             String passwordHash, String role, String status,
                             String currencyCode, String timezone, String fullName) {
    }

    public LoginOutcome login(LoginRequest request) {
        List<Candidate> candidates = findCandidates(request.email(), request.tenantSlug());

        if (candidates.size() > 1) {
            return new LoginOutcome.MultipleTenants(candidates.stream()
                    .map(c -> new TenantSummary(c.tenantId(), c.slug(), c.tenantName()))
                    .toList());
        }

        Candidate candidate = candidates.isEmpty() ? null : candidates.get(0);

        boolean passwordMatches = passwordEncoder.matches(
                request.password(),
                candidate == null || candidate.passwordHash() == null
                        ? DUMMY_HASH : candidate.passwordHash());

        if (candidate == null || !passwordMatches || !"active".equals(candidate.status())) {
            throw new UnauthorizedException("Invalid email or password");
        }

        TenantContext.bind(new TenantContext.TenantIdentity(
                candidate.tenantId(), candidate.userId(), candidate.role()));
        try {
            CurrentUser user = currentUserService.describe(
                    candidate.tenantId(), candidate.userId(), candidate.fullName(),
                    request.email(), candidate.role(), candidate.slug(), candidate.tenantName(),
                    candidate.currencyCode(), candidate.timezone());

            return new LoginOutcome.Authenticated(tokenIssuer.issueFor(
                    candidate.tenantId(), candidate.userId(), candidate.role(),
                    user, request.deviceLabel()));
        } finally {
            TenantContext.clear();
        }
    }

    private List<Candidate> findCandidates(String email, String tenantSlug) {
        String sql = """
                SELECT u.id, u.tenant_id, t.slug, t.name AS tenant_name, u.password_hash,
                       u.role::text AS role, u.status::text AS status,
                       t.currency_code, t.timezone, u.full_name
                FROM users u
                JOIN tenants t ON t.id = u.tenant_id
                WHERE u.email = ?::citext
                  AND u.deleted_at IS NULL
                  AND t.status <> 'closed'
                """;
        Object[] args;
        if (tenantSlug == null || tenantSlug.isBlank()) {
            args = new Object[] {email};
        } else {
            sql += " AND t.slug = ?::citext";
            args = new Object[] {email, tenantSlug};
        }

        return loginJdbc.query(sql, (rs, i) -> new Candidate(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("slug"),
                rs.getString("tenant_name"),
                rs.getString("password_hash"),
                rs.getString("role"),
                rs.getString("status"),
                rs.getString("currency_code"),
                rs.getString("timezone"),
                rs.getString("full_name")), args);
    }

    public sealed interface LoginOutcome {
        record Authenticated(AuthTokens tokens) implements LoginOutcome {
        }

        record MultipleTenants(List<TenantSummary> tenants) implements LoginOutcome {
        }
    }
}
