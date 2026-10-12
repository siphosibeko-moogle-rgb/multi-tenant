package com.example.inventory.forecasting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.inventory.tenancy.TenantContext;

@Service
public class ForecastAccuracyJob {

    private final JdbcTemplate jdbc;

    public ForecastAccuracyJob(@Qualifier("appDataSource") DataSource appDataSource) {
        this.jdbc = new JdbcTemplate(appDataSource);
    }

    public record ScoringResult(int scored) {
    }

    public record MethodAccuracy(ForecastMethod method, int evaluations,
                                 BigDecimal meanAbsPctError) {
    }

    public ScoringResult scoreDueForecasts() {
        return scoreDueForecasts(LocalDate.now());
    }

    @Transactional
    public ScoringResult scoreDueForecasts(LocalDate today) {
        TenantContext.currentTenantId().orElseThrow(() -> new IllegalStateException(
                "ForecastAccuracyJob requires a bound tenant — see DemandRollupJob's Javadoc "
                        + "and CLAUDE.md section 12."));

        List<Due> due = jdbc.query("""
                SELECT f.id,
                       f.product_id,
                       f.location_id,
                       f.horizon_days,
                       f.forecast_qty,
                       (f.generated_at AT TIME ZONE COALESCE(
                            (SELECT timezone FROM tenants WHERE id = current_tenant_id()),
                            'UTC'))::date + 1 AS period_start
                FROM forecasts f
                WHERE f.method <> 'naive'
                  AND f.method <> 'insufficient_data'
                ORDER BY f.id
                """,
                (rs, rowNum) -> new Due(
                        rs.getLong("id"),
                        rs.getObject("product_id", UUID.class),
                        rs.getObject("location_id", UUID.class),
                        rs.getInt("horizon_days"),
                        rs.getBigDecimal("forecast_qty"),
                        rs.getObject("period_start", LocalDate.class)));

        int scored = 0;
        for (Due forecast : due) {
            LocalDate periodEnd = forecast.periodStart().plusDays(forecast.horizonDays());
            if (today.isBefore(periodEnd)) {
                continue;
            }
            if (alreadyScored(forecast.id(), forecast.periodStart())) {
                continue;
            }

            BigDecimal actual = actualDemand(forecast.productId(), forecast.locationId(),
                    forecast.periodStart(), periodEnd);

            score(forecast.id(), forecast.productId(), forecast.periodStart(), periodEnd,
                    forecast.forecastQty(), actual);

            BigDecimal naivePrediction = actualDemand(forecast.productId(),
                    forecast.locationId(),
                    forecast.periodStart().minusDays(forecast.horizonDays()),
                    forecast.periodStart());
            long naiveId = insertNaiveForecast(forecast, naivePrediction);
            score(naiveId, forecast.productId(), forecast.periodStart(), periodEnd,
                    naivePrediction, actual);

            scored++;
        }
        return new ScoringResult(scored);
    }

    private boolean alreadyScored(long forecastId, LocalDate periodStart) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM forecast_accuracy
                WHERE forecast_id = ? AND period_start = ?
                """, Integer.class, forecastId, periodStart);
        return count != null && count > 0;
    }

    private BigDecimal actualDemand(UUID productId, UUID locationId, LocalDate from, LocalDate to) {
        BigDecimal total = jdbc.queryForObject("""
                SELECT COALESCE(sum(units_sold), 0) FROM demand_daily
                WHERE product_id = ? AND location_id = ? AND day >= ? AND day < ?
                """, BigDecimal.class, productId, locationId, from, to);
        return total == null ? BigDecimal.ZERO : total;
    }

    private void score(long forecastId, UUID productId, LocalDate periodStart,
                       LocalDate periodEnd, BigDecimal predicted, BigDecimal actual) {
        jdbc.update("""
                INSERT INTO forecast_accuracy
                    (tenant_id, forecast_id, product_id, period_start, period_end,
                     predicted_qty, actual_qty, abs_pct_error)
                VALUES (current_tenant_id(), ?, ?, ?, ?, ?, ?, ?)
                """,
                forecastId, productId, periodStart, periodEnd, predicted, actual,
                absolutePercentageError(predicted, actual));
    }

    private BigDecimal absolutePercentageError(BigDecimal predicted, BigDecimal actual) {
        if (actual.signum() == 0) {
            return null;
        }
        return predicted.subtract(actual).abs()
                .divide(actual.abs(), DemandSeries.MC)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private long insertNaiveForecast(Due forecast, BigDecimal prediction) {
        BigDecimal dailyRate = prediction.divide(
                BigDecimal.valueOf(forecast.horizonDays()), DemandSeries.MC)
                .setScale(4, RoundingMode.HALF_UP);

        return jdbc.queryForObject("""
                INSERT INTO forecasts
                    (tenant_id, product_id, location_id, method, generated_at, history_days,
                     history_from, history_to, avg_daily_demand, demand_stddev, horizon_days,
                     forecast_qty, service_level, is_current)
                VALUES (current_tenant_id(), ?, ?, 'naive', ?, ?, ?, ?, ?, 0, ?, ?, 0.950, false)
                RETURNING id
                """,
                Long.class,
                forecast.productId(),
                forecast.locationId(),
                forecast.periodStart().atStartOfDay().atOffset(java.time.ZoneOffset.UTC),
                forecast.horizonDays(),
                forecast.periodStart().minusDays(forecast.horizonDays()),
                forecast.periodStart(),
                dailyRate,
                forecast.horizonDays(),
                prediction);
    }

    public List<MethodAccuracy> accuracyByMethod() {
        return jdbc.query("""
                SELECT f.method::text          AS method,
                       count(*)                AS evaluations,
                       avg(a.abs_pct_error)    AS mean_error
                FROM forecast_accuracy a
                JOIN forecasts f ON f.id = a.forecast_id
                GROUP BY f.method
                ORDER BY f.method::text
                """,
                (rs, rowNum) -> new MethodAccuracy(
                        ForecastMethod.fromDbValue(rs.getString("method")),
                        rs.getInt("evaluations"),
                        rs.getBigDecimal("mean_error") == null ? null
                                : rs.getBigDecimal("mean_error").setScale(4, RoundingMode.HALF_UP)));
    }

    private record Due(long id, UUID productId, UUID locationId, int horizonDays,
                       BigDecimal forecastQty, LocalDate periodStart) {
    }
}
