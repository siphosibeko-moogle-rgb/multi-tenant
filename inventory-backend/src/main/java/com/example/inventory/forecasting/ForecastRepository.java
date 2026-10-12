package com.example.inventory.forecasting;

import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.inventory.forecasting.Forecaster.Forecast;

@Repository
public class ForecastRepository {

    private final JdbcTemplate jdbc;

    public ForecastRepository(@Qualifier("appDataSource") DataSource appDataSource) {
        this.jdbc = new JdbcTemplate(appDataSource);
    }

    public long saveAsCurrent(Forecast forecast) {
        jdbc.update("""
                UPDATE forecasts SET is_current = false
                WHERE product_id = ? AND location_id = ? AND is_current
                """, forecast.productId(), forecast.locationId());

        return jdbc.queryForObject("""
                INSERT INTO forecasts
                    (tenant_id, product_id, location_id, method, history_days, history_from,
                     history_to, avg_daily_demand, demand_stddev, horizon_days, forecast_qty,
                     days_of_cover, projected_stockout_on, reorder_point, service_level,
                     confidence, is_current)
                VALUES (current_tenant_id(), ?, ?, CAST(? AS forecast_method), ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, true)
                RETURNING id
                """,
                Long.class,
                forecast.productId(),
                forecast.locationId(),
                forecast.method().dbValue(),
                forecast.selection().historyDays(),
                forecast.historyFrom(),
                forecast.historyTo(),
                forecast.avgDailyDemand(),
                forecast.demandStddev(),
                forecast.horizonDays(),
                forecast.forecastQty(),
                forecast.daysOfCover(),
                forecast.projectedStockoutOn(),
                forecast.reorderPoint(),
                forecast.serviceLevel(),
                forecast.confidence());
    }

    public void expireOpenRecommendation(UUID productId, UUID locationId) {
        jdbc.update("""
                UPDATE reorder_recommendations
                SET status = 'expired', resolved_at = now()
                WHERE product_id = ? AND location_id = ? AND status = 'open'
                """, productId, locationId);
    }

    public UUID saveRecommendation(ReorderService.Recommendation recommendation) {
        return jdbc.queryForObject("""
                INSERT INTO reorder_recommendations
                    (tenant_id, product_id, location_id, supplier_id, forecast_id,
                     quantity_on_hand, reorder_point, recommended_qty, estimated_cost,
                     projected_stockout_on, rationale, status, urgency)
                VALUES (current_tenant_id(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'open',
                        CAST(? AS recommendation_urgency))
                RETURNING id
                """,
                UUID.class,
                recommendation.productId(),
                recommendation.locationId(),
                recommendation.supplierId(),
                recommendation.forecastId(),
                recommendation.quantityOnHand(),
                recommendation.reorderPoint(),
                recommendation.recommendedQty(),
                recommendation.estimatedCost(),
                recommendation.projectedStockoutOn(),
                recommendation.rationale(),
                recommendation.urgency().wireValue());
    }
}
