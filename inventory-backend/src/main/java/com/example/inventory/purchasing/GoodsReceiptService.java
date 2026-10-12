package com.example.inventory.purchasing;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.inventory.inventory.StockLedgerService;
import com.example.inventory.inventory.StockLedgerService.MovementRequest;
import com.example.inventory.purchasing.PurchaseOrderDtos.PurchaseOrderDetail;
import com.example.inventory.purchasing.PurchaseOrderDtos.ReceiptLine;
import com.example.inventory.purchasing.PurchaseOrderDtos.ReceiptRequest;
import com.example.inventory.web.ConflictException;
import com.example.inventory.web.NotFoundException;

@Service
public class GoodsReceiptService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final StockLedgerService ledger;
    private final PurchaseOrderService purchaseOrders;

    public GoodsReceiptService(@Qualifier("appDataSource") DataSource appDataSource,
                               TransactionTemplate transactions,
                               StockLedgerService ledger,
                               PurchaseOrderService purchaseOrders) {
        this.jdbc = new JdbcTemplate(appDataSource);
        this.transactions = transactions;
        this.ledger = ledger;
        this.purchaseOrders = purchaseOrders;
    }

    public PurchaseOrderDetail receive(UUID poId, ReceiptRequest request) {
        return ledger.withAvailableFilledIn(() -> transactions.execute(status -> {
            var locked = jdbc.query(
                    "SELECT status::text AS status FROM purchase_orders "
                            + "WHERE id = ? AND status IN ('ordered', 'partial') FOR UPDATE",
                    (rs, i) -> rs.getString("status"), poId);

            if (locked.isEmpty()) {
                String current = currentStatus(poId);
                if (current == null) {
                    throw new NotFoundException("No such purchase order");
                }
                throw new ConflictException(
                        "This purchase order is " + current + " and cannot receive stock",
                        "po-not-receivable");
            }

            UUID locationId = poLocation(poId);
            OffsetDateTime receivedAt = request.receivedAt() != null ? request.receivedAt() : OffsetDateTime.now();

            for (ReceiptLine line : request.lines()) {
                receiveLine(poId, locationId, receivedAt, line);
            }

            boolean allReceived = allLinesFullyReceived(poId);
            if (allReceived) {
                var receivedAtTimestamp = java.sql.Timestamp.from(receivedAt.toInstant());
                jdbc.update("""
                        UPDATE purchase_orders SET status = 'received', received_at = ?
                        WHERE id = ?
                        """, receivedAtTimestamp, poId);
                recordLeadTimeObservation(poId, receivedAtTimestamp);
            } else {
                jdbc.update("UPDATE purchase_orders SET status = 'partial' WHERE id = ?", poId);
            }

            return purchaseOrders.read(poId).orElseThrow(() -> new NotFoundException("No such purchase order"));
        }));
    }

    private void receiveLine(UUID poId, UUID locationId, OffsetDateTime receivedAt, ReceiptLine line) {
        var current = jdbc.query("""
                SELECT poi.quantity_ordered, poi.quantity_received, poi.unit_cost, p.sku
                FROM purchase_order_items poi
                JOIN products p ON p.id = poi.product_id
                WHERE poi.purchase_order_id = ? AND poi.product_id = ?
                """, (rs, i) -> new Object[] {
                        rs.getBigDecimal("quantity_ordered"),
                        rs.getBigDecimal("quantity_received"),
                        rs.getBigDecimal("unit_cost"),
                        rs.getString("sku"),
                }, poId, line.productId())
                .stream().findFirst()
                .orElseThrow(() -> new NotFoundException(
                        "That product is not a line on this purchase order"));

        BigDecimal ordered = (BigDecimal) current[0];
        BigDecimal receivedSoFar = (BigDecimal) current[1];
        BigDecimal orderedUnitCost = (BigDecimal) current[2];
        String sku = (String) current[3];
        BigDecimal outstanding = ordered.subtract(receivedSoFar);

        if (line.quantityReceived().compareTo(outstanding) > 0) {
            throw new ConflictException(
                    "Cannot receive %s of %s — only %s remain outstanding on this order".formatted(
                            line.quantityReceived().stripTrailingZeros().toPlainString(),
                            sku,
                            outstanding.stripTrailingZeros().toPlainString()),
                    "receipt-exceeds-outstanding");
        }

        BigDecimal unitCost = line.unitCost() != null ? line.unitCost() : orderedUnitCost;

        jdbc.update("""
                UPDATE purchase_order_items SET quantity_received = quantity_received + ?
                WHERE purchase_order_id = ? AND product_id = ?
                """, line.quantityReceived(), poId, line.productId());

        ledger.postWithin(new MovementRequest(
                line.productId(),
                locationId,
                "purchase_receipt",
                line.quantityReceived(),
                unitCost,
                "purchase_order",
                poId,
                null,
                receivedAt));
    }

    private boolean allLinesFullyReceived(UUID poId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT NOT EXISTS (
                    SELECT 1 FROM purchase_order_items
                    WHERE purchase_order_id = ? AND quantity_received < quantity_ordered
                )
                """, Boolean.class, poId));
    }

    private void recordLeadTimeObservation(UUID poId, java.sql.Timestamp receivedAtTimestamp) {
        jdbc.update("""
                INSERT INTO supplier_lead_time_observations
                    (tenant_id, supplier_id, purchase_order_id, ordered_at, received_at, lead_time_days)
                SELECT current_tenant_id(), po.supplier_id, po.id, po.ordered_at, ?,
                       ROUND((EXTRACT(EPOCH FROM (? - po.ordered_at)) / 86400.0)::numeric, 2)
                FROM purchase_orders po
                WHERE po.id = ?
                """, receivedAtTimestamp, receivedAtTimestamp, poId);
    }

    private String currentStatus(UUID poId) {
        return jdbc.query("SELECT status::text AS status FROM purchase_orders WHERE id = ?",
                        (rs, i) -> rs.getString("status"), poId)
                .stream().findFirst().orElse(null);
    }

    private UUID poLocation(UUID poId) {
        return jdbc.queryForObject(
                "SELECT location_id FROM purchase_orders WHERE id = ?", UUID.class, poId);
    }
}
