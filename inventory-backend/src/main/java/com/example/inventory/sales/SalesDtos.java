package com.example.inventory.sales;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class SalesDtos {

    private SalesDtos() {
    }

    public record SaleWriteRequest(
            @NotEmpty @Valid List<SaleLineRequest> lines,
            UUID clientRequestId,
            UUID locationId,
            @Size(max = 200) String customerName,
            @Size(max = 50) String customerPhone,
            @Size(max = 50) String paymentMethod,
            @PositiveOrZero BigDecimal discountAmount,
            OffsetDateTime soldAt) {
    }

    public record SaleLineRequest(
            @NotNull UUID productId,
            @NotNull @Positive BigDecimal quantity,
            @PositiveOrZero BigDecimal unitPrice,
            @PositiveOrZero BigDecimal discountAmount) {
    }

    public record ReturnLine(
            @NotNull UUID productId,
            @NotNull @Positive BigDecimal quantity) {
    }

    public record ReturnRequest(
            @NotEmpty @Valid List<ReturnLine> lines,
            String reason,
            Boolean restock) {

        public boolean restockOrDefault() {
            return restock == null || restock;
        }
    }

    public record VoidRequest(String reason) {
    }

    public record SaleLine(
            UUID productId,
            String sku,
            String name,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal discountAmount,
            BigDecimal lineTotal,
            BigDecimal returnedQuantity,
            BigDecimal outstandingQuantity) {
    }

    public record SaleDetail(
            UUID id,
            String saleNumber,
            String status,
            String customerName,
            int itemCount,
            BigDecimal subtotalAmount,
            BigDecimal discountAmount,
            BigDecimal taxAmount,
            BigDecimal totalAmount,
            String paymentMethod,
            OffsetDateTime soldAt,
            UUID locationId,
            UUID createdBy,
            List<SaleLine> lines) {
    }

    public record Sale(
            UUID id,
            String saleNumber,
            String status,
            String customerName,
            int itemCount,
            BigDecimal subtotalAmount,
            BigDecimal discountAmount,
            BigDecimal taxAmount,
            BigDecimal totalAmount,
            String paymentMethod,
            OffsetDateTime soldAt) {
    }

    public record SalePage(List<Sale> items, String nextCursor) {
    }
}
