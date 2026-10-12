package com.example.inventory.auth;

import java.util.UUID;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.inventory.auth.AuthDtos.AuthTokens;
import com.example.inventory.auth.AuthDtos.CurrentUser;
import com.example.inventory.auth.AuthDtos.TenantRegistrationRequest;
import com.example.inventory.auth.AuthDtos.TenantSummary;
import com.example.inventory.tenancy.TenantContext;
import com.example.inventory.web.ConflictException;

@Service
public class TenantRegistrationService {

    private final JdbcTemplate appJdbc;
    private final TransactionTemplate transactions;
    private final PasswordEncoder passwordEncoder;
    private final TokenIssuer tokenIssuer;
    private final Supplier<UUID> newTenantId;

    @Autowired
    public TenantRegistrationService(@Qualifier("appDataSource") DataSource appDataSource,
                                     TransactionTemplate transactions,
                                     PasswordEncoder passwordEncoder,
                                     TokenIssuer tokenIssuer) {
        this(appDataSource, transactions, passwordEncoder, tokenIssuer, UUID::randomUUID);
    }

    TenantRegistrationService(DataSource appDataSource,
                              TransactionTemplate transactions,
                              PasswordEncoder passwordEncoder,
                              TokenIssuer tokenIssuer,
                              Supplier<UUID> newTenantId) {
        this.appJdbc = new JdbcTemplate(appDataSource);
        this.transactions = transactions;
        this.passwordEncoder = passwordEncoder;
        this.tokenIssuer = tokenIssuer;
        this.newTenantId = newTenantId;
    }

    public AuthTokens register(TenantRegistrationRequest request) {
        UUID tenantId = newTenantId.get();

        TenantContext.bindForRegistration(tenantId);
        try {
            return transactions.execute(status -> createTenantAndOwner(tenantId, request));
        } finally {
            TenantContext.clear();
        }
    }

    private AuthTokens createTenantAndOwner(UUID tenantId, TenantRegistrationRequest request) {
        try {
            appJdbc.update("""
                    INSERT INTO tenants (id, slug, name, status, currency_code, timezone)
                    VALUES (?, ?, ?, 'active', ?, ?)
                    """,
                    tenantId,
                    request.slug(),
                    request.businessName(),
                    request.currencyCodeOrDefault(),
                    request.timezoneOrDefault());
        } catch (DuplicateKeyException e) {
            throw new ConflictException(
                    "A business with that slug already exists", "tenant-slug-taken");
        }

        UUID userId = UUID.randomUUID();
        appJdbc.update("""
                INSERT INTO users (id, tenant_id, email, password_hash, full_name, role, status)
                VALUES (?, ?, ?, ?, ?, 'owner', 'active')
                """,
                userId,
                tenantId,
                request.ownerEmail(),
                passwordEncoder.encode(request.ownerPassword()),
                request.ownerName());

        UUID locationId = UUID.randomUUID();
        appJdbc.update("""
                INSERT INTO locations (id, tenant_id, name, is_default, is_active)
                VALUES (?, ?, 'Main', true, true)
                """,
                locationId,
                tenantId);

        CurrentUser user = new CurrentUser(
                userId,
                request.ownerEmail(),
                request.ownerName(),
                "owner",
                new TenantSummary(tenantId, request.slug(), request.businessName()),
                request.currencyCodeOrDefault(),
                request.timezoneOrDefault(),
                locationId);

        return tokenIssuer.issueFor(tenantId, userId, "owner", user, null);
    }
}
