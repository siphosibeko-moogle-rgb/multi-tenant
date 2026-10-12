package com.example.inventory.reporting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ReportDtos {

    private ReportDtos() {
    }

    public record DashboardSummary(
            String period,
            BigDecimal salesTotal,
            long salesCount,
            BigDecimal grossProfit,
            BigDecimal inventoryValue,
            long lowStockCount,
            long outOfStockCount,
            long openRecommendationCount,
            List<TrendPoint> salesTrend,
            List<TopProductSummary> topProducts) {
    }

    public record TrendPoint(LocalDate day, BigDecimal revenue) {
    }

    public record TopProductSummary(UUID productId, String name, BigDecimal unitsSold) {
    }

    public record SalesSummaryResponse(List<SalesBucket> buckets) {
    }

    public record SalesBucket(
            LocalDate periodStart,
            BigDecimal unitsSold,
            BigDecimal revenue,
            BigDecimal grossProfit) {
    }

    public record InventoryValuation(
            BigDecimal totalCostValue,
            BigDecimal totalRetailValue,
            long productCount,
            LocalDate asOf) {
    }

    public record TopProduct(
            UUID productId,
            String sku,
            String name,
            BigDecimal unitsSold,
            BigDecimal revenue,
            BigDecimal grossMarginPct) {
    }
}
