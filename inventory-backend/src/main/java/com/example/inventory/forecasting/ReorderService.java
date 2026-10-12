package com.example.inventory.forecasting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.inventory.forecasting.ForecastExplainer.Urgency;
import com.example.inventory.forecasting.Forecaster.Forecast;
import com.example.inventory.tenancy.TenantContext;

@Service
public class ReorderService {

    private final DemandRollupJob demandRollup;
    private final DemandSeriesRepository seriesRepository;
    private final Forecaster forecaster;
    private final ForecastExplainer explainer;
    private final ForecastRepository forecasts;
    private final JdbcTemplate jdbc;

    public ReorderService(DemandRollupJob demandRollup,
                          DemandSeriesRepository seriesRepository,
                          Forecaster forecaster,
                          ForecastExplainer explainer,
                          ForecastRepository forecasts,
                          @Qualifier("appDataSource") DataSource appDataSource) {
        this.demandRollup = demandRollup;
        this.seriesRepository = seriesRepository;
        this.forecaster = forecaster;
        this.explainer = explainer;
        this.forecasts = forecasts;
        this.jdbc = new JdbcTemplate(appDataSource);
    }

    public record Recommendation(
            UUID productId,
            UUID locationId,
            UUID supplierId,
            Long forecastId,
            BigDecimal quantityOnHand,
            BigDecimal reorderPoint,
            BigDecimal recommendedQty,
            BigDecimal estimatedCost,
            LocalDate projectedStockoutOn,
            Urgency urgency,
            String rationale) {
    }

    public record RecomputeResult(int forecastsWritten, int recommendationsWritten) {
    }

    @Transactional
    public RecomputeResult recomputeAll() {
        TenantContext.currentTenantId().orElseThrow(() -> new IllegalStateException(
                "ReorderService requires a bound tenant — see DemandRollupJob's Javadoc "
                        + "and CLAUDE.md section 12."));

        demandRollup.rollUp();

        Map<UUID, ProductFacts> facts = productFacts();
        List<DemandSeries> allSeries = seriesRepository.loadAll();

        int forecastCount = 0;
        int recommendationCount = 0;

        for (DemandSeries series : allSeries) {
            ProductFacts product = facts.get(series.productId());
            if (product == null) {
                continue;
            }

            Forecast forecast = forecaster.forecast(series, LocalDate.now());
            long forecastId = forecasts.saveAsCurrent(forecast);
            forecastCount++;

            forecasts.expireOpenRecommendation(series.productId(), series.locationId());

            Optional<Recommendation> recommendation =
                    recommendationFor(forecast, forecastId, product);
            if (recommendation.isPresent()) {
                forecasts.saveRecommendation(recommendation.get());
                recommendationCount++;
            }
        }
        return new RecomputeResult(forecastCount, recommendationCount);
    }

    Optional<Recommendation> recommendationFor(Forecast forecast, Long forecastId,
                                               ProductFacts product) {
        if (!forecast.hasReorderPoint()) {
            return Optional.empty();
        }
        if (forecast.quantityOnHand().compareTo(forecast.reorderPoint()) > 0) {
            return Optional.empty();
        }

        BigDecimal target = forecast.reorderPoint().add(
                forecast.avgDailyDemand().multiply(
                        BigDecimal.valueOf(forecast.horizonDays()), DemandSeries.MC));
        BigDecimal quantity = target.subtract(forecast.quantityOnHand())
                .setScale(3, RoundingMode.HALF_UP);

        if (quantity.signum() <= 0) {
            return Optional.empty();
        }

        Urgency urgency = urgencyFor(forecast);
        BigDecimal estimatedCost = product.unitCost() == null ? null
                : quantity.multiply(product.unitCost(), DemandSeries.MC)
                        .setScale(2, RoundingMode.HALF_UP);

        String rationale = explainer.explainRecommendation(
                forecast, quantity, urgency, product.supplierName());

        return Optional.of(new Recommendation(
                forecast.productId(), forecast.locationId(),
                forecast.leadTime() == null ? null : forecast.leadTime().supplierId(),
                forecastId, forecast.quantityOnHand(), forecast.reorderPoint(),
                quantity, estimatedCost, forecast.projectedStockoutOn(), urgency, rationale));
    }

    private Urgency urgencyFor(Forecast forecast) {
        if (forecast.quantityOnHand().signum() <= 0) {
            return Urgency.CRITICAL;
        }
        if (forecast.daysOfCover() == null || forecast.leadTime() == null) {
            return Urgency.NORMAL;
        }
        BigDecimal leadTimeDays = forecast.leadTime().days();
        if (forecast.daysOfCover().compareTo(leadTimeDays) < 0) {
            return Urgency.CRITICAL;
        }
        if (forecast.daysOfCover().compareTo(
                leadTimeDays.multiply(BigDecimal.valueOf(2))) < 0) {
            return Urgency.HIGH;
        }
        return Urgency.NORMAL;
    }

    public record ProductFacts(UUID productId, String sku, String name,
                               String supplierName, BigDecimal unitCost) {
    }

    private Map<UUID, ProductFacts> productFacts() {
        List<ProductFacts> rows = jdbc.query("""
                SELECT p.id, p.sku, p.name,
                       s.name       AS supplier_name,
                       COALESCE(ps.unit_cost, p.cost_price) AS unit_cost
                FROM products p
                LEFT JOIN product_suppliers ps
                       ON ps.tenant_id = p.tenant_id AND ps.product_id = p.id
                      AND ps.is_preferred
                LEFT JOIN suppliers s
                       ON s.tenant_id = ps.tenant_id AND s.id = ps.supplier_id
                WHERE p.deleted_at IS NULL AND p.is_tracked
                """,
                (rs, rowNum) -> new ProductFacts(
                        rs.getObject("id", UUID.class),
                        rs.getString("sku"),
                        rs.getString("name"),
                        rs.getString("supplier_name"),
                        rs.getBigDecimal("unit_cost")));

        Map<UUID, ProductFacts> byId = new java.util.HashMap<>();
        for (ProductFacts row : rows) {
            byId.put(row.productId(), row);
        }
        return byId;
    }

    public record ExplainedForecast(Forecast forecast, String explanation) {
    }

    public ExplainedForecast explainedForecast(UUID productId, UUID locationId) {
        Forecast forecast = forecaster.forecast(productId, locationId);
        ProductFacts product = productFacts().get(productId);
        return new ExplainedForecast(forecast,
                explainer.explain(forecast, product == null ? null : product.name()));
    }
}
