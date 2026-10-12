package com.example.inventory.catalog;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.inventory.catalog.CatalogDtos.Category;
import com.example.inventory.catalog.CatalogDtos.CategoryWriteRequest;
import com.example.inventory.catalog.CatalogDtos.Location;
import com.example.inventory.catalog.CatalogDtos.LocationWriteRequest;
import com.example.inventory.catalog.CatalogDtos.Product;
import com.example.inventory.catalog.CatalogDtos.ProductPage;
import com.example.inventory.catalog.CatalogDtos.ProductWriteRequest;
import com.example.inventory.inventory.StockLedgerService;
import com.example.inventory.inventory.StockLedgerService.MovementRequest;
import com.example.inventory.tenancy.TenantContext;
import com.example.inventory.web.ConflictException;
import com.example.inventory.web.NotFoundException;
import com.example.inventory.web.PreconditionFailedException;

@Service
public class ProductCatalog {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private static final String PRODUCT_COLUMNS = """
            SELECT p.id, p.sku, p.barcode, p.name, p.description, p.category_id,
                   p.unit_of_measure, p.cost_price, p.selling_price, p.tax_rate,
                   COALESCE(p.reorder_point, MAX(f.reorder_point)) AS reorder_point,
                   p.is_tracked, p.is_active, p.allow_negative_stock,
                   p.updated_at, p.version,
                   COALESCE(SUM(ps.quantity_on_hand), 0)   AS quantity_on_hand,
                   COALESCE(SUM(ps.quantity_available), 0) AS quantity_available
            FROM products p
            LEFT JOIN product_stock ps ON ps.product_id = p.id
            LEFT JOIN forecasts f
                   ON f.tenant_id = p.tenant_id AND f.product_id = p.id AND f.is_current
            """;

    private static final String PRODUCT_GROUP_BY = " GROUP BY p.id ";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final StockLedgerService ledger;

    public ProductCatalog(@Qualifier("appDataSource") DataSource appDataSource,
                          TransactionTemplate transactions,
                          StockLedgerService ledger) {
        this.jdbc = new JdbcTemplate(appDataSource);
        this.transactions = transactions;
        this.ledger = ledger;
    }

    public ProductPage list(String cursor, Integer limit, String q, UUID categoryId,
                            Boolean active) {
        int pageSize = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        List<Object> args = new ArrayList<>();

        StringBuilder sql = new StringBuilder(PRODUCT_COLUMNS).append(" WHERE p.deleted_at IS NULL");

        if (q != null && !q.isBlank()) {
            sql.append(" AND (p.name ILIKE ? OR p.sku ILIKE ? OR p.barcode ILIKE ?)");
            String pattern = "%" + q.trim() + "%";
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
        }
        if (categoryId != null) {
            sql.append(" AND p.category_id = ?");
            args.add(categoryId);
        }
        if (active != null) {
            sql.append(" AND p.is_active = ?");
            args.add(active);
        }

        String decoded = decodeCursor(cursor);
        if (decoded != null) {
            sql.append(" AND (upper(p.sku), p.id::text) > (?, ?)");
            args.add(decoded.substring(0, decoded.lastIndexOf('|')).toUpperCase());
            args.add(decoded.substring(decoded.lastIndexOf('|') + 1));
        }

        sql.append(PRODUCT_GROUP_BY)
                .append(" ORDER BY upper(p.sku), p.id LIMIT ").append(pageSize + 1);

        List<Product> rows = jdbc.query(sql.toString(), ProductCatalog::mapProduct, args.toArray());

        boolean hasMore = rows.size() > pageSize;
        List<Product> page = hasMore ? rows.subList(0, pageSize) : rows;
        String next = hasMore && !page.isEmpty()
                ? encodeCursor(page.get(page.size() - 1).sku() + "|" + page.get(page.size() - 1).id())
                : null;

        return new ProductPage(page, next);
    }

    public Optional<Versioned<Product>> find(UUID productId) {
        return jdbc.query(PRODUCT_COLUMNS + " WHERE p.id = ? AND p.deleted_at IS NULL"
                        + PRODUCT_GROUP_BY, ProductCatalog::mapVersioned, productId)
                .stream().findFirst();
    }

    public Optional<Versioned<Product>> findByBarcode(String barcode) {
        return jdbc.query(PRODUCT_COLUMNS + " WHERE p.barcode = ? AND p.deleted_at IS NULL"
                        + PRODUCT_GROUP_BY, ProductCatalog::mapVersioned, barcode)
                .stream().findFirst();
    }

    public Versioned<Product> create(ProductWriteRequest request) {
        UUID tenantId = TenantContext.currentTenantId()
                .orElseThrow(() -> new IllegalStateException("no tenant bound"));
        UUID id = UUID.randomUUID();

        return transactions.execute(status -> {
            try {
                jdbc.update("""
                        INSERT INTO products
                            (id, tenant_id, sku, barcode, name, description, category_id,
                             unit_of_measure, cost_price, selling_price, tax_rate,
                             reorder_point, reorder_quantity, safety_stock,
                             is_tracked, allow_negative_stock)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?,
                                COALESCE(?, 0), COALESCE(?, 0), COALESCE(?, 0),
                                ?, ?, ?, COALESCE(?, true), COALESCE(?, false))
                        """,
                        id, tenantId, request.sku(), blankToNull(request.barcode()), request.name(),
                        request.description(), request.categoryId(),
                        request.unitOfMeasureOrDefault(), request.costPrice(),
                        request.sellingPrice(), request.taxRate(), request.reorderPoint(),
                        request.reorderQuantity(), request.safetyStock(),
                        request.isTracked(), request.allowNegativeStock());
            } catch (DuplicateKeyException e) {
                throw conflictFor(e);
            }

            postOpeningStock(request, id);

            return find(id).orElseThrow(
                    () -> new IllegalStateException("product vanished after insert"));
        });
    }

    private void postOpeningStock(ProductWriteRequest request, UUID productId) {
        var opening = request.openingStock();
        if (opening == null || opening.quantity() == null
                || opening.quantity().signum() <= 0) {
            return;
        }
        UUID locationId = opening.locationId() != null
                ? opening.locationId()
                : defaultLocationId().orElseThrow(() -> new ConflictException(
                        "This business has no default location; specify openingStock.locationId",
                        "no-default-location"));

        ledger.post(new MovementRequest(productId, locationId, "opening_balance",
                opening.quantity(), opening.unitCost(), null, null, "opening stock", null));
    }

    public Versioned<Product> update(UUID productId, ProductWriteRequest request,
                                     String ifMatch) {
        return transactions.execute(status -> {
            Long expectedVersion = ifMatch == null ? null : ETags.parse(ifMatch);

            StringBuilder sql = new StringBuilder("""
                    UPDATE products SET
                        sku = ?, barcode = ?, name = ?, description = ?, category_id = ?,
                        unit_of_measure = ?, cost_price = COALESCE(?, cost_price),
                        selling_price = COALESCE(?, selling_price),
                        tax_rate = COALESCE(?, tax_rate), reorder_point = ?,
                        reorder_quantity = ?, safety_stock = ?,
                        is_tracked = COALESCE(?, is_tracked),
                        allow_negative_stock = COALESCE(?, allow_negative_stock)
                    WHERE id = ? AND deleted_at IS NULL
                    """);
            List<Object> args = new ArrayList<>(List.of());
            args.add(request.sku());
            args.add(blankToNull(request.barcode()));
            args.add(request.name());
            args.add(request.description());
            args.add(request.categoryId());
            args.add(request.unitOfMeasureOrDefault());
            args.add(request.costPrice());
            args.add(request.sellingPrice());
            args.add(request.taxRate());
            args.add(request.reorderPoint());
            args.add(request.reorderQuantity());
            args.add(request.safetyStock());
            args.add(request.isTracked());
            args.add(request.allowNegativeStock());
            args.add(productId);

            if (expectedVersion != null) {
                sql.append(" AND version = ?");
                args.add(expectedVersion);
            }

            int updated;
            try {
                updated = jdbc.update(sql.toString(), args.toArray());
            } catch (DuplicateKeyException e) {
                throw conflictFor(e);
            }

            if (updated == 0) {
                if (find(productId).isPresent()) {
                    throw new PreconditionFailedException(
                            "This product was modified by someone else; re-read it and retry");
                }
                throw new NotFoundException("No such product");
            }

            return find(productId).orElseThrow(() -> new NotFoundException("No such product"));
        });
    }

    public boolean deactivate(UUID productId) {
        return jdbc.update("""
                UPDATE products SET is_active = false, deleted_at = now()
                WHERE id = ? AND deleted_at IS NULL
                """, productId) > 0;
    }

    public List<Product> findAllByIds(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        return jdbc.query(PRODUCT_COLUMNS + " WHERE p.id IN (" + placeholders + ")"
                        + " AND p.deleted_at IS NULL" + PRODUCT_GROUP_BY,
                        ProductCatalog::mapProduct, ids.toArray());
    }

    public List<Category> findCategoriesByIds(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        return jdbc.query("""
                SELECT c.id, c.name, c.parent_id,
                       (SELECT count(*) FROM products p
                         WHERE p.category_id = c.id AND p.deleted_at IS NULL) AS product_count
                FROM categories c WHERE c.id IN (%s) ORDER BY c.name
                """.formatted(placeholders), (rs, i) -> new Category(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getObject("parent_id", UUID.class),
                        rs.getLong("product_count")), ids.toArray());
    }

    public List<Location> findLocationsByIds(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        return jdbc.query("""
                SELECT id, name, address, is_default, is_active FROM locations
                WHERE id IN (%s) ORDER BY is_default DESC, name
                """.formatted(placeholders), (rs, i) -> new Location(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("address"),
                        rs.getBoolean("is_default"),
                        rs.getBoolean("is_active")), ids.toArray());
    }

    public List<Category> categories() {
        return jdbc.query("""
                SELECT c.id, c.name, c.parent_id,
                       (SELECT count(*) FROM products p
                         WHERE p.category_id = c.id AND p.deleted_at IS NULL) AS product_count
                FROM categories c ORDER BY c.name
                """, (rs, i) -> new Category(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getObject("parent_id", UUID.class),
                        rs.getLong("product_count")));
    }

    public Category createCategory(CategoryWriteRequest request) {
        UUID tenantId = TenantContext.currentTenantId()
                .orElseThrow(() -> new IllegalStateException("no tenant bound"));
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO categories (id, tenant_id, name, parent_id) VALUES (?, ?, ?, ?)",
                    id, tenantId, request.name(), request.parentId());
        } catch (DuplicateKeyException e) {
            throw new ConflictException("A category with that name already exists",
                    "category-name-taken");
        }
        return new Category(id, request.name(), request.parentId(), 0);
    }

    public List<Location> locations() {
        return jdbc.query("SELECT id, name, address, is_default, is_active FROM locations "
                        + "ORDER BY is_default DESC, name",
                (rs, i) -> new Location(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("address"),
                        rs.getBoolean("is_default"),
                        rs.getBoolean("is_active")));
    }

    public Location createLocation(LocationWriteRequest request) {
        UUID tenantId = TenantContext.currentTenantId()
                .orElseThrow(() -> new IllegalStateException("no tenant bound"));
        UUID id = UUID.randomUUID();
        boolean isDefault = Boolean.TRUE.equals(request.isDefault());

        return transactions.execute(status -> {
            if (isDefault) {
                jdbc.update("UPDATE locations SET is_default = false WHERE is_default");
            }
            try {
                jdbc.update("""
                        INSERT INTO locations (id, tenant_id, name, address, is_default)
                        VALUES (?, ?, ?, ?, ?)
                        """, id, tenantId, request.name(), request.address(), isDefault);
            } catch (DuplicateKeyException e) {
                throw new ConflictException("A location with that name already exists",
                        "location-name-taken");
            }
            return new Location(id, request.name(), request.address(), isDefault, true);
        });
    }

    public Optional<UUID> defaultLocationId() {
        return jdbc.query("SELECT id FROM locations WHERE is_default LIMIT 1",
                        (rs, i) -> rs.getObject("id", UUID.class))
                .stream().findFirst();
    }

    private static ConflictException conflictFor(DuplicateKeyException e) {
        String message = String.valueOf(e.getMostSpecificCause().getMessage());
        if (message.contains("products_tenant_barcode_uq")) {
            return new ConflictException(
                    "Another product in this business already uses that barcode",
                    "product-barcode-taken");
        }
        return new ConflictException(
                "Another product in this business already uses that SKU", "product-sku-taken");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Versioned<Product> mapVersioned(java.sql.ResultSet rs, int row)
            throws java.sql.SQLException {
        return new Versioned<>(mapProduct(rs, row), rs.getLong("version"));
    }

    private static Product mapProduct(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        BigDecimal onHand = rs.getBigDecimal("quantity_on_hand");
        BigDecimal available = rs.getBigDecimal("quantity_available");
        BigDecimal reorderPoint = rs.getBigDecimal("reorder_point");

        return new Product(
                rs.getObject("id", UUID.class),
                rs.getString("sku"),
                rs.getString("barcode"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getObject("category_id", UUID.class),
                rs.getString("unit_of_measure"),
                rs.getBigDecimal("cost_price"),
                rs.getBigDecimal("selling_price"),
                rs.getBigDecimal("tax_rate"),
                reorderPoint,
                onHand,
                available,
                stockState(onHand, available, reorderPoint),
                rs.getBoolean("is_tracked"),
                rs.getBoolean("is_active"),
                rs.getBoolean("allow_negative_stock"),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private static String stockState(BigDecimal onHand, BigDecimal available,
                                     BigDecimal reorderPoint) {
        if (onHand == null || onHand.signum() <= 0) {
            return "out_of_stock";
        }
        if (reorderPoint == null) {
            return "ok";
        }
        return available != null && available.compareTo(reorderPoint) <= 0 ? "reorder" : "ok";
    }

    private static String encodeCursor(String raw) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeCursor(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            return raw.contains("|") ? raw : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
