package com.example.inventory.purchasing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public final class PurchaseOrderDtos {

    private PurchaseOrderDtos() {
    }

    public record PurchaseOrderWriteRequest(
            @NotNull UUID supplierId,
            UUID locationId,
            LocalDate expectedAt,
            String notes,
            List<UUID> fromRecommendationIds,
            @NotEmpty @Valid List<PurchaseOrderLineRequest> lines) {
    }

    public record PurchaseOrderLineRequest(
            @NotNull UUID productId,
            @NotNull @Positive BigDecimal quantityOrdered,
            @PositiveOrZero BigDecimal unitCost) {
    }

    public record PurchaseOrder(
            UUID id,
            String poNumber,
            UUID supplierId,
            String supplierName,
            String status,
            OffsetDateTime orderedAt,
            LocalDate expectedAt,
            OffsetDateTime receivedAt,
            BigDecimal totalAmount,
            int lineCount) {
    }

    public record PurchaseOrderLine(
            UUID productId,
            String sku,
            String name,
            BigDecimal quantityOrdered,
            BigDecimal quantityReceived,
            BigDecimal quantityOutstanding,
            BigDecimal unitCost,
            BigDecimal lineTotal) {
    }

    public record PurchaseOrderDetail(
            UUID id,
            String poNumber,
            UUID supplierId,
            String supplierName,
            String status,
            OffsetDateTime orderedAt,
            LocalDate expectedAt,
            OffsetDateTime receivedAt,
            BigDecimal totalAmount,
            int lineCount,
            UUID locationId,
            String notes,
            List<PurchaseOrderLine> lines) {
    }

    public record PurchaseOrderPage(List<PurchaseOrder> items, String nextCursor) {
    }

    public record ReceiptRequest(
            OffsetDateTime receivedAt,
            @NotEmpty @Valid List<ReceiptLine> lines) {
    }

    public record ReceiptLine(
            @NotNull UUID productId,
            @NotNull @Positive BigDecimal quantityReceived,
            @PositiveOrZero BigDecimal unitCost) {
    }
}
