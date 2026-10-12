package com.example.inventory.sales;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.inventory.inventory.StockLedgerService;
import com.example.inventory.inventory.StockLedgerService.MovementRequest;
import com.example.inventory.sales.SalesDtos.SaleDetail;
import com.example.inventory.sales.SalesDtos.SaleLine;
import com.example.inventory.sales.SalesDtos.SaleWriteRequest;
import com.example.inventory.tenancy.TenantContext;
import com.example.inventory.web.ConflictException;
import com.example.inventory.web.NotFoundException;

@Service
public class SaleService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final StockLedgerService ledger;

    public SaleService(@Qualifier("appDataSource") DataSource appDataSource,
                       TransactionTemplate transactions,
                       StockLedgerService ledger) {
        this.jdbc = new JdbcTemplate(appDataSource);
        this.transactions = transactions;
        this.ledger = ledger;
    }

    public record Recorded(SaleDetail sale, boolean replayed) {
    }

    public Recorded record(SaleWriteRequest request) {
        return record(request, (UUID) null);
    }

    public Recorded record(SaleWriteRequest request, UUID idempotencyKeyHeader) {
        UUID tenantId = TenantContext.currentTenantId()
                .orElseThrow(() -> new IllegalStateException("no tenant bound"));
        UUID actor = TenantContext.currentUserId().orElse(null);
        UUID idempotencyKey = request.clientRequestId() != null
                ? request.clientRequestId() : idempotencyKeyHeader;

        if (idempotencyKey != null) {
            Optional<SaleDetail> existing = findByClientRequestId(idempotencyKey);
            if (existing.isPresent()) {
                return new Recorded(existing.get(), true);
            }
        }

        try {
            return new Recorded(ledger.withAvailableFilledIn(
                    () -> transactions.execute(status ->
                            insertSale(tenantId, actor, request, idempotencyKey))),
                    false);
        } catch (DuplicateKeyException e) {
            if (idempotencyKey != null) {
                return new Recorded(findByClientRequestId(idempotencyKey)
                        .orElseThrow(() -> e), true);
            }
            throw e;
        }
    }

    public SaleDetail voidSale(UUID saleId, String reason) {
        UUID actor = TenantContext.currentUserId().orElse(null);

        return ledger.withAvailableFilledIn(() -> transactions.execute(status -> {
            int updated = jdbc.update(
                    "UPDATE sales SET status = 'voided', updated_at = now() "
                            + "WHERE id = ? AND status = 'completed'", saleId);

            if (updated == 0) {
                String current = currentStatus(saleId);
                if (current == null) {
                    throw new NotFoundException("No such sale");
                }
                throw new ConflictException(
                        "This sale is " + current + " and cannot be voided",
                        "sale-not-voidable");
            }

            returnOutstanding(saleId, actor, reason, true, true, null);
            return read(saleId).orElseThrow(() -> new NotFoundException("No such sale"));
        }));
    }

    public SaleDetail returnSale(UUID saleId, SalesDtos.ReturnRequest request) {
        UUID actor = TenantContext.currentUserId().orElse(null);

        return ledger.withAvailableFilledIn(() -> transactions.execute(status -> {
            List<String> locked = jdbc.query(
                    "SELECT status::text AS status FROM sales WHERE id = ? "
                            + "AND status IN ('completed', 'partially_refunded') FOR UPDATE",
                    (rs, i) -> rs.getString("status"), saleId);

            if (locked.isEmpty()) {
                String current = currentStatus(saleId);
                if (current == null) {
                    throw new NotFoundException("No such sale");
                }
                throw new ConflictException(
                        "This sale is " + current + " and cannot accept a return",
                        "sale-not-returnable");
            }

            returnOutstanding(saleId, actor, request.reason(),
                    request.restockOrDefault(), false, request.lines());

            BigDecimal stillOutstanding = outstandingByProduct(saleId).stream()
                    .map(Outstanding::outstanding)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            String newStatus = stillOutstanding.signum() == 0 ? "refunded" : "partially_refunded";

            jdbc.update("UPDATE sales SET status = ?::sale_status, updated_at = now() WHERE id = ?",
                    newStatus, saleId);

            return read(saleId).orElseThrow(() -> new NotFoundException("No such sale"));
        }));
    }

    private String currentStatus(UUID saleId) {
        return jdbc.query("SELECT status::text AS status FROM sales WHERE id = ?",
                        (rs, i) -> rs.getString("status"), saleId)
                .stream().findFirst().orElse(null);
    }

    private void returnOutstanding(UUID saleId, UUID actor, String reason,
                                   boolean restock, boolean isVoid,
                                   List<SalesDtos.ReturnLine> requested) {
        UUID groupId = UUID.randomUUID();
        UUID locationId = saleLocation(saleId);

        for (var outstanding : outstandingByProduct(saleId)) {
            BigDecimal quantity = requested == null
                    ? outstanding.outstanding()
                    : requested.stream()
                            .filter(l -> l.productId().equals(outstanding.productId()))
                            .map(SalesDtos.ReturnLine::quantity)
                            .findFirst().orElse(BigDecimal.ZERO);

            if (quantity.signum() <= 0) {
                continue;
            }
            if (quantity.compareTo(outstanding.outstanding()) > 0) {
                throw new ConflictException(
                        "Cannot return %s of %s — only %s remain on this sale".formatted(
                                quantity.stripTrailingZeros().toPlainString(),
                                outstanding.sku(),
                                outstanding.outstanding().stripTrailingZeros().toPlainString()),
                        "return-exceeds-sale");
            }

            jdbc.update("""
                    INSERT INTO sale_returns
                        (tenant_id, sale_id, product_id, quantity, restocked,
                         return_group_id, is_void, reason, created_by)
                    VALUES (current_tenant_id(), ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    saleId, outstanding.productId(), quantity, restock,
                    groupId, isVoid, reason, actor);

            if (!restock) {
                continue;
            }

            ledger.postWithin(new MovementRequest(
                    outstanding.productId(),
                    locationId,
                    "sale_return",
                    quantity,
                    null,
                    "sale",
                    saleId,
                    reason,
                    null));
        }
    }

    private record Outstanding(UUID productId, String sku, BigDecimal outstanding) {
    }

    private List<Outstanding> outstandingByProduct(UUID saleId) {
        return jdbc.query("""
                SELECT si.product_id,
                       p.sku,
                       si.quantity - COALESCE((
                           SELECT SUM(sr.quantity) FROM sale_returns sr
                           WHERE sr.sale_id = si.sale_id
                             AND sr.product_id = si.product_id
                       ), 0) AS outstanding
                FROM sale_items si
                JOIN products p ON p.id = si.product_id
                WHERE si.sale_id = ?
                ORDER BY p.sku
                """, (rs, i) -> new Outstanding(
                        rs.getObject("product_id", UUID.class),
                        rs.getString("sku"),
                        rs.getBigDecimal("outstanding")), saleId);
    }

    private UUID saleLocation(UUID saleId) {
        return jdbc.queryForObject(
                "SELECT location_id FROM sales WHERE id = ?", UUID.class, saleId);
    }

    private SaleDetail insertSale(UUID tenantId, UUID actor, SaleWriteRequest request,
                                  UUID idempotencyKey) {
        UUID locationId = request.locationId() != null
                ? request.locationId()
                : defaultLocationId().orElseThrow(() -> new ConflictException(
                        "This business has no default location; specify locationId",
                        "no-default-location"));

        UUID saleId = UUID.randomUUID();
        OffsetDateTime soldAt = request.soldAt() != null ? request.soldAt() : OffsetDateTime.now();
        BigDecimal saleDiscount = request.discountAmount() == null
                ? BigDecimal.ZERO : request.discountAmount();

        jdbc.update("""
                INSERT INTO sales (id, tenant_id, location_id, sale_number, status,
                                   customer_name, customer_phone, payment_method,
                                   discount_amount, sold_at, created_by, client_request_id)
                VALUES (?, ?, ?, ?, 'completed', ?, ?, ?, ?, ?, ?, ?)
                """,
                saleId, tenantId, locationId, nextSaleNumber(tenantId), request.customerName(),
                request.customerPhone(), request.paymentMethod(), saleDiscount,
                java.sql.Timestamp.from(soldAt.toInstant()), actor, idempotencyKey);

        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;

        for (var line : request.lines()) {
            var product = readProduct(line.productId());

            BigDecimal unitPrice = line.unitPrice() != null ? line.unitPrice() : product.sellingPrice();
            BigDecimal lineDiscount = line.discountAmount() == null
                    ? BigDecimal.ZERO : line.discountAmount();
            BigDecimal lineTotal = unitPrice.multiply(line.quantity())
                    .subtract(lineDiscount)
                    .setScale(2, RoundingMode.HALF_UP);

            jdbc.update("""
                    INSERT INTO sale_items (tenant_id, sale_id, product_id, quantity,
                                            unit_price, unit_cost, discount_amount, tax_rate)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    tenantId, saleId, line.productId(), line.quantity(), unitPrice,
                    product.costPrice(), lineDiscount, product.taxRate());

            ledger.postWithin(new MovementRequest(
                    line.productId(), locationId, "sale",
                    line.quantity().abs().negate(), product.costPrice(),
                    "sale", saleId, null, soldAt));

            subtotal = subtotal.add(lineTotal);
            tax = tax.add(lineTotal.multiply(product.taxRate()).setScale(2, RoundingMode.HALF_UP));
        }

        BigDecimal total = subtotal.subtract(saleDiscount).add(tax).setScale(2, RoundingMode.HALF_UP);

        jdbc.update("""
                UPDATE sales SET subtotal_amount = ?, tax_amount = ?, total_amount = ?
                WHERE id = ?
                """, subtotal, tax, total, saleId);

        return read(saleId).orElseThrow(() -> new IllegalStateException("sale vanished after insert"));
    }

    private String nextSaleNumber(UUID tenantId) {
        long assigned = jdbc.queryForObject("""
                UPDATE tenants SET next_sale_number = next_sale_number + 1
                WHERE id = ? RETURNING next_sale_number - 1
                """, Long.class, tenantId);
        return "S-%06d".formatted(assigned);
    }

    private record ProductPricing(BigDecimal sellingPrice, BigDecimal costPrice, BigDecimal taxRate) {
    }

    private ProductPricing readProduct(UUID productId) {
        return jdbc.query("""
                SELECT selling_price, cost_price, tax_rate FROM products
                WHERE id = ? AND deleted_at IS NULL
                """,
                (rs, i) -> new ProductPricing(
                        rs.getBigDecimal("selling_price"),
                        rs.getBigDecimal("cost_price"),
                        rs.getBigDecimal("tax_rate")), productId)
                .stream().findFirst()
                .orElseThrow(() -> new NotFoundException("No such product"));
    }

    private Optional<UUID> defaultLocationId() {
        return jdbc.query("SELECT id FROM locations WHERE is_default LIMIT 1",
                        (rs, i) -> rs.getObject("id", UUID.class))
                .stream().findFirst();
    }

    private Optional<SaleDetail> findByClientRequestId(UUID clientRequestId) {
        return jdbc.query("SELECT id FROM sales WHERE client_request_id = ?",
                        (rs, i) -> rs.getObject("id", UUID.class), clientRequestId)
                .stream().findFirst()
                .flatMap(this::read);
    }

    private static final String TENANT_ZONE =
            "COALESCE((SELECT timezone FROM tenants WHERE id = current_tenant_id()), 'UTC')";

    public SalesDtos.SalePage list(String cursor, Integer limit, LocalDate from, LocalDate to,
                                   String status) {
        int pageSize = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);

        StringBuilder sql = new StringBuilder("""
                SELECT s.id, s.sale_number, s.status::text AS status, s.customer_name,
                       (SELECT COUNT(*) FROM sale_items si WHERE si.sale_id = s.id) AS item_count,
                       s.subtotal_amount, s.discount_amount, s.tax_amount, s.total_amount,
                       s.payment_method, s.sold_at
                FROM sales s
                WHERE 1 = 1
                """);
        var args = new java.util.ArrayList<Object>();

        if (status != null && !status.isBlank()) {
            sql.append(" AND s.status = ?::sale_status");
            args.add(status);
        }
        if (from != null) {
            sql.append(" AND s.sold_at >= (CAST(? AS date)::timestamp AT TIME ZONE " + TENANT_ZONE + ")");
            args.add(java.sql.Date.valueOf(from));
        }
        if (to != null) {
            sql.append(" AND s.sold_at < (CAST(? AS date)::timestamp AT TIME ZONE " + TENANT_ZONE + ")");
            args.add(java.sql.Date.valueOf(to.plusDays(1)));
        }
        ListCursor decoded = ListCursor.decode(cursor);
        if (decoded != null) {
            sql.append(" AND (s.sold_at, s.id) < (?, ?)");
            args.add(java.sql.Timestamp.from(decoded.soldAt().toInstant()));
            args.add(decoded.id());
        }
        sql.append(" ORDER BY s.sold_at DESC, s.id DESC LIMIT ").append(pageSize + 1);

        List<SalesDtos.Sale> rows = jdbc.query(sql.toString(), (rs, i) -> new SalesDtos.Sale(
                rs.getObject("id", UUID.class),
                rs.getString("sale_number"),
                rs.getString("status"),
                rs.getString("customer_name"),
                rs.getInt("item_count"),
                rs.getBigDecimal("subtotal_amount"),
                rs.getBigDecimal("discount_amount"),
                rs.getBigDecimal("tax_amount"),
                rs.getBigDecimal("total_amount"),
                rs.getString("payment_method"),
                rs.getObject("sold_at", OffsetDateTime.class)), args.toArray());

        boolean hasMore = rows.size() > pageSize;
        List<SalesDtos.Sale> page = hasMore ? rows.subList(0, pageSize) : rows;

        String nextCursor = hasMore && !page.isEmpty()
                ? new ListCursor(page.get(page.size() - 1).soldAt(), page.get(page.size() - 1).id())
                        .encode()
                : null;

        return new SalesDtos.SalePage(page, nextCursor);
    }

    private record ListCursor(OffsetDateTime soldAt, UUID id) {

        String encode() {
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (soldAt + "|" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        static ListCursor decode(String encoded) {
            if (encoded == null || encoded.isBlank()) {
                return null;
            }
            try {
                String raw = new String(java.util.Base64.getUrlDecoder().decode(encoded),
                        java.nio.charset.StandardCharsets.UTF_8);
                int split = raw.lastIndexOf('|');
                return new ListCursor(
                        OffsetDateTime.parse(raw.substring(0, split)),
                        UUID.fromString(raw.substring(split + 1)));
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    public Optional<SaleDetail> read(UUID saleId) {
        var sales = jdbc.query("""
                SELECT id, sale_number, status::text AS status, customer_name,
                       subtotal_amount, discount_amount, tax_amount, total_amount,
                       payment_method, sold_at, location_id, created_by
                FROM sales WHERE id = ?
                """, (rs, i) -> new Object[] {
                        rs.getObject("id", UUID.class),
                        rs.getString("sale_number"),
                        rs.getString("status"),
                        rs.getString("customer_name"),
                        rs.getBigDecimal("subtotal_amount"),
                        rs.getBigDecimal("discount_amount"),
                        rs.getBigDecimal("tax_amount"),
                        rs.getBigDecimal("total_amount"),
                        rs.getString("payment_method"),
                        rs.getObject("sold_at", OffsetDateTime.class),
                        rs.getObject("location_id", UUID.class),
                        rs.getObject("created_by", UUID.class),
                }, saleId);

        if (sales.isEmpty()) {
            return Optional.empty();
        }
        Object[] s = sales.get(0);

        List<SaleLine> lines = jdbc.query("""
                SELECT si.product_id, p.sku, p.name, si.quantity, si.unit_price,
                       si.discount_amount, si.line_total,
                       COALESCE((
                           SELECT SUM(sr.quantity) FROM sale_returns sr
                           WHERE sr.sale_id = si.sale_id AND sr.product_id = si.product_id
                       ), 0) AS returned_quantity
                FROM sale_items si
                JOIN products p ON p.id = si.product_id
                WHERE si.sale_id = ?
                ORDER BY p.sku
                """, (rs, i) -> {
                    BigDecimal quantity = rs.getBigDecimal("quantity");
                    BigDecimal returned = rs.getBigDecimal("returned_quantity");
                    return new SaleLine(
                            rs.getObject("product_id", UUID.class),
                            rs.getString("sku"),
                            rs.getString("name"),
                            quantity,
                            rs.getBigDecimal("unit_price"),
                            rs.getBigDecimal("discount_amount"),
                            rs.getBigDecimal("line_total"),
                            returned,
                            quantity.subtract(returned));
                }, saleId);

        return Optional.of(new SaleDetail(
                (UUID) s[0], (String) s[1], (String) s[2], (String) s[3],
                lines.size(),
                (BigDecimal) s[4], (BigDecimal) s[5], (BigDecimal) s[6], (BigDecimal) s[7],
                (String) s[8], (OffsetDateTime) s[9], (UUID) s[10], (UUID) s[11], lines));
    }
}
