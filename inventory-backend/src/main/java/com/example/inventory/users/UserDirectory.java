package com.example.inventory.users;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.example.inventory.tenancy.TenantContext;
import com.example.inventory.users.UserDtos.User;
import com.example.inventory.users.UserDtos.UserInviteRequest;
import com.example.inventory.users.UserDtos.UserPage;
import com.example.inventory.users.UserDtos.UserUpdateRequest;
import com.example.inventory.web.ConflictException;

@Service
public class UserDirectory {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final JdbcTemplate jdbc;

    public UserDirectory(@Qualifier("appDataSource") DataSource appDataSource) {
        this.jdbc = new JdbcTemplate(appDataSource);
    }

    public UserPage list(String cursor, Integer limit, String status) {
        int pageSize = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);

        StringBuilder sql = new StringBuilder("""
                SELECT id, email::text AS email, full_name, role::text AS role,
                       status::text AS status, last_login_at, created_at
                FROM users
                WHERE deleted_at IS NULL
                """);
        var args = new java.util.ArrayList<Object>();

        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?::user_status");
            args.add(status);
        }
        Cursor decoded = Cursor.decode(cursor);
        if (decoded != null) {
            sql.append(" AND (created_at, id) > (?, ?)");
            args.add(java.sql.Timestamp.from(decoded.createdAt().toInstant()));
            args.add(decoded.id());
        }
        sql.append(" ORDER BY created_at, id LIMIT ").append(pageSize + 1);

        List<Row> rows = jdbc.query(sql.toString(), (rs, i) -> new Row(
                new User(
                        rs.getObject("id", UUID.class),
                        rs.getString("email"),
                        rs.getString("full_name"),
                        rs.getString("role"),
                        rs.getString("status"),
                        rs.getObject("last_login_at", OffsetDateTime.class)),
                rs.getObject("created_at", OffsetDateTime.class)), args.toArray());

        boolean hasMore = rows.size() > pageSize;
        List<Row> page = hasMore ? rows.subList(0, pageSize) : rows;

        String nextCursor = hasMore && !page.isEmpty()
                ? new Cursor(page.get(page.size() - 1).createdAt(),
                        page.get(page.size() - 1).user().id()).encode()
                : null;

        return new UserPage(page.stream().map(Row::user).toList(), nextCursor);
    }

    public Optional<User> find(UUID userId) {
        return jdbc.query("""
                SELECT id, email::text AS email, full_name, role::text AS role,
                       status::text AS status, last_login_at
                FROM users WHERE id = ? AND deleted_at IS NULL
                """, (rs, i) -> new User(
                        rs.getObject("id", UUID.class),
                        rs.getString("email"),
                        rs.getString("full_name"),
                        rs.getString("role"),
                        rs.getString("status"),
                        rs.getObject("last_login_at", OffsetDateTime.class)), userId)
                .stream().findFirst();
    }

    public User invite(UserInviteRequest request) {
        UUID tenantId = TenantContext.currentTenantId()
                .orElseThrow(() -> new IllegalStateException("no tenant bound"));
        UUID id = UUID.randomUUID();

        try {
            jdbc.update("""
                    INSERT INTO users (id, tenant_id, email, full_name, role, status)
                    VALUES (?, ?, ?, ?, ?::user_role, 'invited')
                    """, id, tenantId, request.email(), request.fullName(), request.role());
        } catch (DuplicateKeyException e) {
            throw new ConflictException(
                    "A user with that email already exists in this business", "user-email-taken");
        }

        return find(id).orElseThrow(() -> new IllegalStateException("user vanished after insert"));
    }

    public Optional<User> update(UUID userId, UserUpdateRequest request) {
        if (find(userId).isEmpty()) {
            return Optional.empty();
        }

        if (request.role() != null) {
            jdbc.update("UPDATE users SET role = ?::user_role WHERE id = ?", request.role(), userId);
        }
        if (request.status() != null) {
            jdbc.update("UPDATE users SET status = ?::user_status WHERE id = ?",
                    request.status(), userId);
        }
        if (request.fullName() != null) {
            jdbc.update("UPDATE users SET full_name = ? WHERE id = ?", request.fullName(), userId);
        }
        return find(userId);
    }

    public boolean deactivate(UUID userId) {
        return jdbc.update("""
                UPDATE users SET status = 'disabled', deleted_at = now()
                WHERE id = ? AND deleted_at IS NULL
                """, userId) > 0;
    }

    private record Row(User user, OffsetDateTime createdAt) {
    }

    private record Cursor(OffsetDateTime createdAt, UUID id) {

        String encode() {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (createdAt + "|" + id).getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String encoded) {
            if (encoded == null || encoded.isBlank()) {
                return null;
            }
            try {
                String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
                int split = raw.lastIndexOf('|');
                return new Cursor(
                        OffsetDateTime.parse(raw.substring(0, split)),
                        UUID.fromString(raw.substring(split + 1)));
            } catch (RuntimeException e) {
                return null;
            }
        }
    }
}
