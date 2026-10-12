package com.example.inventory.inventory;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class InventoryDtos {

    private InventoryDtos() {
    }

    public record AdjustmentRequest(
            @NotNull UUID productId,
            UUID locationId,
            @NotNull BigDecimal quantityDelta,
            @NotNull @Size(min = 1, max = 500) String reason,
            @PositiveOrZero BigDecimal unitCost,
            OffsetDateTime occurredAt) {
    }

    public record TransferRequest(
            @NotNull UUID productId,
            @NotNull UUID fromLocationId,
            @NotNull UUID toLocationId,
            @NotNull @Positive BigDecimal quantity,
            @Size(max = 500) String reason,
            OffsetDateTime occurredAt) {
    }

    public record StockMovement(
            long id,
            UUID productId,
            String productName,
            UUID locationId,
            String movementType,
            BigDecimal quantityDelta,
            BigDecimal balanceAfter,
            BigDecimal unitCost,
            String referenceType,
            UUID referenceId,
            String reason,
            OffsetDateTime occurredAt,
            UUID createdBy,
            String createdByName) {
    }

    public record StockStatus(
            UUID productId,
            String sku,
            String name,
            UUID locationId,
            String locationName,
            BigDecimal quantityOnHand,
            BigDecimal quantityReserved,
            BigDecimal quantityAvailable,
            BigDecimal reorderPoint,
            String stockState,
            BigDecimal daysOfCover,
            OffsetDateTime updatedAt) {
    }

    public record MovementPage(List<StockMovement> items, String nextCursor) {
    }

    public record StockStatusPage(List<StockStatus> items, String nextCursor) {
    }
}
