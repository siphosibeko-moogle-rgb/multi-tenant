package com.example.inventory.inventory;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.inventory.tenancy.TenantContext;
import com.example.inventory.web.SqlStates;
import com.example.inventory.web.ConflictException;
import com.example.inventory.web.NotFoundException;

@Service
public class StockLedgerService {

    private static final String CHECK_VIOLATION = SqlStates.CHECK_VIOLATION;

    private static final String OVERSELL_MESSAGE = "Insufficient stock for product";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public StockLedgerService(@Qualifier("appDataSource") DataSource appDataSource,
                              TransactionTemplate transactions) {
        this.jdbc = new JdbcTemplate(appDataSource);
        this.transactions = transactions;
    }

    public record MovementRequest(
            UUID productId,
            UUID locationId,
            String movementType,
            BigDecimal quantityDelta,
            BigDecimal unitCost,
            String referenceType,
            UUID referenceId,
            String reason,
            OffsetDateTime occurredAt) {
    }

    public record TransferRequest(
            UUID productId,
            UUID fromLocationId,
            UUID toLocationId,
            BigDecimal quantity,
            String reason,
            UUID transferId,
            OffsetDateTime occurredAt) {
    }

    public record Transfer(PostedMovement out, PostedMovement in) {
    }

    public record PostedMovement(
            long id,
            UUID productId,
            String productName,
            UUID locationId,
            String movementType,
            BigDecimal quantityDelta,
            BigDecimal balanceAfter,
            BigDecimal unitCost,
            String reason,
            OffsetDateTime occurredAt,
            UUID createdBy,
            String createdByName) {
    }

    public PostedMovement post(MovementRequest request) {
        return withAvailableFilledIn(() -> transactions.execute(status -> postWithin(request)));
    }

    public <T> T withAvailableFilledIn(java.util.function.Supplier<T> work) {
        try {
            return work.get();
        } catch (InsufficientStockException e) {
            if (e.available() != null) {
                throw e;
            }
            throw e.withAvailable(availableFor(e.productId(), e.locationId()));
        }
    }

    public Transfer transfer(TransferRequest request) {
        return withAvailableFilledIn(() -> transferWithin(request));
    }

    private Transfer transferWithin(TransferRequest request) {
        if (request.fromLocationId().equals(request.toLocationId())) {
            throw new ConflictException(
                    "A transfer needs two different locations", "transfer-same-location");
        }

        return transactions.execute(status -> {
            PostedMovement out = postWithin(new MovementRequest(
                    request.productId(), request.fromLocationId(), "transfer_out",
                    request.quantity().abs().negate(), null,
                    "transfer", request.transferId(), request.reason(), request.occurredAt()));

            PostedMovement in = postWithin(new MovementRequest(
                    request.productId(), request.toLocationId(), "transfer_in",
                    request.quantity().abs(), null,
                    "transfer", request.transferId(), request.reason(), request.occurredAt()));

            return new Transfer(out, in);
        });
    }

    public record Posted(PostedMovement movement, boolean replayed) {
    }

    public Posted post(MovementRequest request, UUID clientRequestId) {
        if (clientRequestId == null) {
            return new Posted(post(request), false);
        }

        Optional<PostedMovement> existing = findByClientRequestId(clientRequestId);
        if (existing.isPresent()) {
            return new Posted(existing.get(), true);
        }

        try {
            return new Posted(withAvailableFilledIn(
                    () -> transactions.execute(status -> postWithin(request, clientRequestId))),
                    false);
        } catch (DuplicateKeyException e) {
            return new Posted(findByClientRequestId(clientRequestId).orElseThrow(() -> e), true);
        }
    }

    private Optional<PostedMovement> findByClientRequestId(UUID clientRequestId) {
        return jdbc.query("SELECT id FROM stock_movements WHERE client_request_id = ?",
                        (rs, i) -> rs.getLong("id"), clientRequestId)
                .stream().findFirst().map(this::readPosted);
    }

    public PostedMovement postWithin(MovementRequest request) {
        return postWithin(request, null);
    }

    private PostedMovement postWithin(MovementRequest request, UUID clientRequestId) {
        UUID tenantId = TenantContext.currentTenantId()
                .orElseThrow(() -> new IllegalStateException(
                        "no tenant bound — a ledger write must run inside a tenant-bound request"));
        UUID actor = TenantContext.currentUserId().orElse(null);

        long id;
        try {
            id = insert(tenantId, actor, request, clientRequestId);
        } catch (DuplicateKeyException e) {
            throw e;
        } catch (DataAccessException e) {
            throw translate(e, request);
        }

        BigDecimal balanceAfter = jdbc.queryForObject("""
                SELECT quantity_on_hand FROM product_stock
                WHERE product_id = ? AND location_id = ?
                """, BigDecimal.class, request.productId(), request.locationId());

        Echo echo = readEcho(id);

        return new PostedMovement(
                id,
                request.productId(),
                echo.productName(),
                request.locationId(),
                request.movementType(),
                request.quantityDelta(),
                balanceAfter,
                request.unitCost(),
                request.reason(),
                echo.occurredAt(),
                echo.createdBy(),
                echo.createdByName());
    }

    private long insert(UUID tenantId, UUID actor, MovementRequest request) {
        return insert(tenantId, actor, request, null);
    }

    private long insert(UUID tenantId, UUID actor, MovementRequest request, UUID clientRequestId) {
        return jdbc.queryForObject("""
                INSERT INTO stock_movements
                    (tenant_id, product_id, location_id, movement_type, quantity_delta,
                     unit_cost, reference_type, reference_id, reason, occurred_at, created_by,
                     client_request_id)
                VALUES (?, ?, ?, ?::movement_type, ?, ?, ?, ?, ?,
                        COALESCE(?, now()), ?, ?)
                RETURNING id
                """, Long.class,
                tenantId,
                request.productId(),
                request.locationId(),
                request.movementType(),
                request.quantityDelta(),
                request.unitCost(),
                request.referenceType(),
                request.referenceId(),
                request.reason(),
                request.occurredAt() == null ? null : java.sql.Timestamp.from(
                        request.occurredAt().toInstant()),
                actor,
                clientRequestId);
    }

    private PostedMovement readPosted(long id) {
        return jdbc.queryForObject("""
                SELECT m.id, m.product_id, p.name AS product_name, m.location_id,
                       m.movement_type::text AS movement_type, m.quantity_delta,
                       m.unit_cost, m.reason, m.occurred_at, m.created_by,
                       u.full_name AS created_by_name,
                       COALESCE(ps.quantity_on_hand, 0) AS balance_after
                FROM stock_movements m
                JOIN products p
                     ON p.tenant_id = m.tenant_id AND p.id = m.product_id
                LEFT JOIN users u
                     ON u.tenant_id = m.tenant_id AND u.id = m.created_by
                LEFT JOIN product_stock ps
                     ON ps.tenant_id = m.tenant_id AND ps.product_id = m.product_id
                    AND ps.location_id = m.location_id
                WHERE m.id = ?
                """,
                (rs, row) -> new PostedMovement(
                        rs.getLong("id"),
                        rs.getObject("product_id", UUID.class),
                        rs.getString("product_name"),
                        rs.getObject("location_id", UUID.class),
                        rs.getString("movement_type"),
                        rs.getBigDecimal("quantity_delta"),
                        rs.getBigDecimal("balance_after"),
                        rs.getBigDecimal("unit_cost"),
                        rs.getString("reason"),
                        rs.getObject("occurred_at", OffsetDateTime.class),
                        rs.getObject("created_by", UUID.class),
                        rs.getString("created_by_name")),
                id);
    }

    private record Echo(OffsetDateTime occurredAt, String productName,
                        UUID createdBy, String createdByName) {
    }

    private Echo readEcho(long id) {
        return jdbc.queryForObject("""
                SELECT m.occurred_at,
                       p.name       AS product_name,
                       m.created_by,
                       u.full_name  AS created_by_name
                FROM stock_movements m
                JOIN products p
                     ON p.tenant_id = m.tenant_id AND p.id = m.product_id
                LEFT JOIN users u
                     ON u.tenant_id = m.tenant_id AND u.id = m.created_by
                WHERE m.id = ?
                """,
                (rs, row) -> new Echo(
                        rs.getObject("occurred_at", OffsetDateTime.class),
                        rs.getString("product_name"),
                        rs.getObject("created_by", UUID.class),
                        rs.getString("created_by_name")),
                id);
    }

    private RuntimeException translate(DataAccessException e, MovementRequest request) {
        String sqlState = SqlStates.of(e);
        String message = SqlStates.rootMessage(e);

        if (CHECK_VIOLATION.equals(sqlState) && message != null
                && message.contains(OVERSELL_MESSAGE)) {
            return new InsufficientStockException(
                    request.productId(), request.locationId(), request.quantityDelta().abs());
        }
        if (SqlStates.FOREIGN_KEY_VIOLATION.equals(sqlState)) {
            return new NotFoundException("No such product or location in this business");
        }
        return e;
    }

    private BigDecimal availableFor(UUID productId, UUID locationId) {
        try {
            return jdbc.query("""
                    SELECT quantity_available FROM product_stock
                    WHERE product_id = ? AND location_id = ?
                    """,
                    rs -> rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO,
                    productId, locationId);
        } catch (DataAccessException ignored) {
            return BigDecimal.ZERO;
        }
    }
}
