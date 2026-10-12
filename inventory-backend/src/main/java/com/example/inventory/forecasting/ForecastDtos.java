package com.example.inventory.forecasting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class ForecastDtos {

    private ForecastDtos() {
    }

    public record ForecastResponse(
            UUID productId,
            UUID locationId,
            String method,
            OffsetDateTime generatedAt,
            Integer historyDays,
            BigDecimal avgDailyDemand,
            BigDecimal demandStddev,
            Integer horizonDays,
            BigDecimal forecastQty,
            BigDecimal daysOfCover,
            LocalDate projectedStockoutOn,
            BigDecimal reorderPoint,
            BigDecimal serviceLevel,
            BigDecimal confidence) {
    }

    public record HistoryPoint(LocalDate day, BigDecimal unitsSold, boolean hadStockout) {
    }

    public record ForecastDetailResponse(
            UUID productId,
            UUID locationId,
            String method,
            OffsetDateTime generatedAt,
            Integer historyDays,
            BigDecimal avgDailyDemand,
            BigDecimal demandStddev,
            Integer horizonDays,
            BigDecimal forecastQty,
            BigDecimal daysOfCover,
            LocalDate projectedStockoutOn,
            BigDecimal reorderPoint,
            BigDecimal serviceLevel,
            BigDecimal confidence,
            String explanation,
            List<HistoryPoint> history) {
    }

    public record ForecastPage(List<ForecastResponse> items, String nextCursor) {
    }

    public record RecommendationResponse(
            UUID id,
            UUID productId,
            String sku,
            String name,
            UUID locationId,
            UUID supplierId,
            String supplierName,
            BigDecimal quantityOnHand,
            BigDecimal reorderPoint,
            BigDecimal recommendedQty,
            BigDecimal estimatedCost,
            LocalDate projectedStockoutOn,
            String urgency,
            String rationale,
            String status,
            OffsetDateTime createdAt) {
    }

    public record RecommendationPage(List<RecommendationResponse> items, String nextCursor) {
    }

    public record RecomputeRequest(List<UUID> productIds) {
    }

    public record RecomputeAccepted(UUID jobId, int queuedProducts) {
    }

    public record DismissRequest(String reason) {
    }
}
