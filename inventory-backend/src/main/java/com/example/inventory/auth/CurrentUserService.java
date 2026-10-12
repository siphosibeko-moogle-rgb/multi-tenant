package com.example.inventory.auth;

import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.example.inventory.auth.AuthDtos.CurrentUser;
import com.example.inventory.auth.AuthDtos.TenantSummary;

@Service
public class CurrentUserService {

    private final JdbcTemplate appJdbc;

    public CurrentUserService(@Qualifier("appDataSource") DataSource appDataSource) {
        this.appJdbc = new JdbcTemplate(appDataSource);
    }

    public Optional<CurrentUser> load(UUID tenantId, UUID userId) {
        var rows = appJdbc.query("""
                SELECT u.id, u.email::text AS email, u.full_name, u.role::text AS role,
                       t.id AS tenant_id, t.slug::text AS slug, t.name AS tenant_name,
                       t.currency_code, t.timezone,
                       (SELECT l.id FROM locations l
                         WHERE l.tenant_id = t.id AND l.is_default
                         LIMIT 1) AS default_location_id
                FROM users u
                JOIN tenants t ON t.id = u.tenant_id
                WHERE u.id = ? AND u.tenant_id = ? AND u.deleted_at IS NULL
                """,
                (rs, i) -> new CurrentUser(
                        rs.getObject("id", UUID.class),
                        rs.getString("email"),
                        rs.getString("full_name"),
                        rs.getString("role"),
                        new TenantSummary(
                                rs.getObject("tenant_id", UUID.class),
                                rs.getString("slug"),
                                rs.getString("tenant_name")),
                        rs.getString("currency_code"),
                        rs.getString("timezone"),
                        rs.getObject("default_location_id", UUID.class)),
                userId, tenantId);

        return rows.stream().findFirst();
    }

    CurrentUser describe(UUID tenantId, UUID userId, String fullName, String email, String role,
                         String slug, String tenantName, String currencyCode, String timezone) {
        UUID defaultLocationId = appJdbc.query(
                "SELECT id FROM locations WHERE tenant_id = ? AND is_default LIMIT 1",
                (rs, i) -> rs.getObject("id", UUID.class), tenantId)
                .stream().findFirst().orElse(null);

        return new CurrentUser(userId, email, fullName, role,
                new TenantSummary(tenantId, slug, tenantName),
                currencyCode, timezone, defaultLocationId);
    }
}
