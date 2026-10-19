package com.example.inventory.directory;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import com.example.inventory.directory.DirectoryDtos.BusinessType;
import com.example.inventory.directory.DirectoryDtos.SupplierContact;
import com.example.inventory.directory.DirectoryDtos.SupplierListing;
import com.example.inventory.directory.DirectoryDtos.SupplierListingOwn;
import com.example.inventory.directory.DirectoryDtos.SupplierListingPage;
import com.example.inventory.directory.DirectoryDtos.SupplierListingWrite;
import com.example.inventory.tenancy.TenantContext;
import com.example.inventory.web.ConflictException;
import com.example.inventory.web.NotFoundException;
import com.example.inventory.web.RateLimitedException;

/**
 * The supplier directory is the ONLY place data crosses between businesses.
 * Never join these tables to products, stock, sales or users.
 */
@Service
public class DirectoryService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;
    /** Distinct listings one business may reveal per hour (anti-scraping). */
    private static final int MAX_CONTACT_REQUESTS_PER_HOUR = 20;

    private static final String PUBLIC_COLUMNS =
            "id, business_name, business_type, contact_name, description, category";

    private final JdbcTemplate jdbc;

    public DirectoryService(@Qualifier("appDataSource") DataSource appDataSource) {
        this.jdbc = new JdbcTemplate(appDataSource);
    }

    // ---- search / view (no contact details, ever) ----------------------------------

    public SupplierListingPage search(String q, String category, BusinessType businessType,
                                      String cursor, Integer limit) {
        UUID tenantId = requireTenant();
        int pageSize = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);

        StringBuilder sql = new StringBuilder("SELECT " + PUBLIC_COLUMNS + ", created_at "
                + "FROM supplier_listing WHERE is_published AND tenant_id <> ?");
        var args = new ArrayList<Object>();
        args.add(tenantId);

        if (q != null && !q.isBlank()) {
            sql.append(" AND (business_name ILIKE ? OR description ILIKE ? OR category ILIKE ?)");
            String pattern = "%" + escapeLike(q.trim()) + "%";
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
        }
        if (category != null && !category.isBlank()) {
            sql.append(" AND category ILIKE ?");
            args.add(escapeLike(category.trim()));
        }
        if (businessType != null) {
            sql.append(" AND business_type = ?");
            args.add(businessType.name());
        }
        ListCursor decoded = ListCursor.decode(cursor);
        if (decoded != null) {
            sql.append(" AND (created_at, id) < (?, ?)");
            args.add(Timestamp.from(decoded.createdAt().toInstant()));
            args.add(decoded.id());
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ").append(pageSize + 1);

        List<Row> rows = jdbc.query(sql.toString(), (rs, i) -> new Row(
                publicListing(rs, i), rs.getObject("created_at", OffsetDateTime.class)), args.toArray());

        boolean hasMore = rows.size() > pageSize;
        List<Row> page = hasMore ? rows.subList(0, pageSize) : rows;
        String nextCursor = hasMore && !page.isEmpty()
                ? new ListCursor(page.get(page.size() - 1).createdAt(), page.get(page.size() - 1).listing().id())
                        .encode()
                : null;

        return new SupplierListingPage(page.stream().map(Row::listing).toList(), nextCursor);
    }

    public Optional<SupplierListing> read(UUID listingId) {
        return jdbc.query("SELECT " + PUBLIC_COLUMNS + " FROM supplier_listing "
                        + "WHERE id = ? AND is_published",
                this::publicListing, listingId).stream().findFirst();
    }

    // ---- request contact (the only place phone/email leave for another business) ----

    public SupplierContact requestContact(UUID listingId) {
        UUID tenantId = requireTenant();
        UUID userId = TenantContext.currentUserId()
                .orElseThrow(() -> new IllegalStateException("no user bound"));

        var target = jdbc.query("""
                SELECT tenant_id, phone, email FROM supplier_listing
                WHERE id = ? AND is_published
                """, (rs, i) -> new Object[] {
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("phone"),
                        rs.getString("email") }, listingId)
                .stream().findFirst()
                .orElseThrow(() -> new NotFoundException("No such listing"));

        if (tenantId.equals(target[0])) {
            throw new ConflictException("This is your own listing", "own-listing");
        }

        // One row per (listing, requesting business): asking again just re-reveals.
        Integer already = jdbc.queryForObject("""
                SELECT count(*) FROM supplier_contact_request
                WHERE listing_id = ? AND requester_tenant_id = ?
                """, Integer.class, listingId, tenantId);

        if (already == null || already == 0) {
            Integer recent = jdbc.queryForObject("""
                    SELECT count(*) FROM supplier_contact_request
                    WHERE requester_tenant_id = ? AND created_at > now() - interval '1 hour'
                    """, Integer.class, tenantId);
            if (recent != null && recent >= MAX_CONTACT_REQUESTS_PER_HOUR) {
                throw new RateLimitedException(
                        "Too many contact requests. Try again later.", Duration.ofHours(1));
            }
            jdbc.update("""
                    INSERT INTO supplier_contact_request
                        (listing_id, requester_tenant_id, requester_user_id, status)
                    VALUES (?, ?, ?, 'REVEALED')
                    """, listingId, tenantId, userId);
        }
        return new SupplierContact((String) target[1], (String) target[2]);
    }

    // ---- my own listing (tenant always comes from TenantContext, never the body) ----

    public Optional<SupplierListingOwn> readMine() {
        UUID tenantId = requireTenant();
        return jdbc.query("SELECT " + PUBLIC_COLUMNS + ", phone, email, is_published "
                        + "FROM supplier_listing WHERE tenant_id = ?",
                OWN_MAPPER, tenantId).stream().findFirst();
    }

    public SupplierListingOwn upsertMine(SupplierListingWrite w) {
        UUID tenantId = requireTenant();
        return jdbc.queryForObject("""
                INSERT INTO supplier_listing
                    (tenant_id, business_name, business_type, contact_name,
                     phone, email, description, category)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id) DO UPDATE SET
                    business_name = EXCLUDED.business_name,
                    business_type = EXCLUDED.business_type,
                    contact_name  = EXCLUDED.contact_name,
                    phone         = EXCLUDED.phone,
                    email         = EXCLUDED.email,
                    description   = EXCLUDED.description,
                    category      = EXCLUDED.category,
                    is_published  = TRUE,
                    updated_at    = now()
                RETURNING """ + " " + PUBLIC_COLUMNS + ", phone, email, is_published",
                OWN_MAPPER,
                tenantId, w.businessName().trim(), w.businessType().name(), w.contactName().trim(),
                w.phone().trim(), w.email().trim(), w.description().trim(), w.category().trim());
    }

    /** Withdraw = hard delete, so phone/email are really gone (POPIA). Requests cascade. */
    public void withdrawMine() {
        UUID tenantId = requireTenant();
        int deleted = jdbc.update("DELETE FROM supplier_listing WHERE tenant_id = ?", tenantId);
        if (deleted == 0) {
            throw new NotFoundException("Your business has no listing");
        }
    }

    // ---- helpers ---------------------------------------------------------------------

    private static UUID requireTenant() {
        return TenantContext.currentTenantId()
                .orElseThrow(() -> new IllegalStateException("no tenant bound"));
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private SupplierListing publicListing(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new SupplierListing(
                rs.getObject("id", UUID.class),
                rs.getString("business_name"),
                BusinessType.valueOf(rs.getString("business_type")),
                rs.getString("contact_name"),
                rs.getString("description"),
                rs.getString("category"));
    }

    private static final RowMapper<SupplierListingOwn> OWN_MAPPER = (rs, i) -> new SupplierListingOwn(
            rs.getObject("id", UUID.class),
            rs.getString("business_name"),
            BusinessType.valueOf(rs.getString("business_type")),
            rs.getString("contact_name"),
            rs.getString("description"),
            rs.getString("category"),
            rs.getString("phone"),
            rs.getString("email"),
            rs.getBoolean("is_published"));

    private record Row(SupplierListing listing, OffsetDateTime createdAt) {
    }

    private record ListCursor(OffsetDateTime createdAt, UUID id) {

        String encode() {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (createdAt + "|" + id).getBytes(StandardCharsets.UTF_8));
        }

        static ListCursor decode(String encoded) {
            if (encoded == null || encoded.isBlank()) {
                return null;
            }
            try {
                String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
                int split = raw.lastIndexOf('|');
                return new ListCursor(
                        OffsetDateTime.parse(raw.substring(0, split)),
                        UUID.fromString(raw.substring(split + 1)));
            } catch (RuntimeException e) {
                return null;
            }
        }
    }
}
