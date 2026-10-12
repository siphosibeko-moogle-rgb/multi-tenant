package com.example.inventory.forecasting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.example.inventory.forecasting.ForecastDtos.ForecastPage;
import com.example.inventory.forecasting.ForecastDtos.ForecastResponse;
import com.example.inventory.forecasting.ForecastDtos.HistoryPoint;
import com.example.inventory.forecasting.ForecastDtos.RecommendationPage;
import com.example.inventory.forecasting.ForecastDtos.RecommendationResponse;

@Service
public class ForecastQueries {

    private final JdbcTemplate jdbc;

    public ForecastQueries(@Qualifier("appDataSource") DataSource appDataSource) {
        this.jdbc = new JdbcTemplate(appDataSource);
    }

    public ForecastPage listForecasts(UUID locationId, Integer horizonDays,
                                      String cursor, int limit) {
        ForecastCursor from = ForecastCursor.decode(cursor);

        StringBuilder sql = new StringBuilder(FORECAST_COLUMNS + """
                FROM forecasts f
                WHERE f.is_current
                """);
        List<Object> args = new ArrayList<>();
        if (locationId != null) {
            sql.append(" AND f.location_id = ?");
            args.add(locationId);
        }
        if (from != null) {
            sql.append(" AND (f.generated_at, f.id) < (?, ?)");
            args.add(from.generatedAt());
            args.add(from.id());
        }
        sql.append(" ORDER BY f.generated_at DESC, f.id DESC LIMIT ?");
        args.add(limit + 1);

        List<ForecastRow> rows = jdbc.query(sql.toString(), this::mapForecast, args.toArray());

        boolean more = rows.size() > limit;
        List<ForecastRow> page = more ? rows.subList(0, limit) : rows;
        ForecastRow last = page.isEmpty() ? null : page.get(page.size() - 1);
        String nextCursor = more && last != null
                ? new ForecastCursor(last.generatedAt(), last.id()).encode()
                : null;

        return new ForecastPage(
                page.stream().map(row -> rescale(row.forecast(), horizonDays)).toList(),
                nextCursor);
    }

    public Optional<ForecastResponse> currentForecast(UUID productId, UUID locationId) {
        StringBuilder sql = new StringBuilder(FORECAST_COLUMNS + """
                FROM forecasts f
                WHERE f.is_current AND f.product_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(productId);
        if (locationId != null) {
            sql.append(" AND f.location_id = ?");
            args.add(locationId);
        }
        sql.append(" ORDER BY f.generated_at DESC, f.id DESC LIMIT 1");

        return jdbc.query(sql.toString(), this::mapForecast, args.toArray())
                .stream().findFirst().map(ForecastRow::forecast);
    }

    public List<HistoryPoint> history(UUID productId, UUID locationId) {
        StringBuilder sql = new StringBuilder("""
                SELECT day, units_sold, had_stockout FROM demand_daily
                WHERE product_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(productId);
        if (locationId != null) {
            sql.append(" AND location_id = ?");
            args.add(locationId);
        }
        sql.append(" ORDER BY day");

        return jdbc.query(sql.toString(),
                (rs, n) -> new HistoryPoint(
                        rs.getObject("day", LocalDate.class),
                        rs.getBigDecimal("units_sold"),
                        rs.getBoolean("had_stockout")),
                args.toArray());
    }

    public Optional<UUID> defaultLocationId() {
        return jdbc.query("""
                SELECT id FROM locations
                WHERE is_active
                ORDER BY is_default DESC, created_at
                LIMIT 1
                """, (rs, n) -> rs.getObject("id", UUID.class))
                .stream().findFirst();
    }

    public boolean productExists(UUID productId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM products WHERE id = ? AND deleted_at IS NULL",
                Integer.class, productId);
        return count != null && count > 0;
    }

    public RecommendationPage listRecommendations(String status, UUID supplierId,
                                                  String cursor, int limit) {
        RecommendationCursor from = RecommendationCursor.decode(cursor);

        StringBuilder sql = new StringBuilder("""
                SELECT r.id, r.product_id, p.sku, p.name, r.location_id, r.supplier_id,
                       s.name AS supplier_name, r.quantity_on_hand, r.reorder_point,
                       r.recommended_qty, r.estimated_cost, r.projected_stockout_on,
                       r.urgency::text AS urgency, r.rationale, r.status::text AS status,
                       r.created_at
                FROM reorder_recommendations r
                JOIN products p ON p.tenant_id = r.tenant_id AND p.id = r.product_id
                LEFT JOIN suppliers s ON s.tenant_id = r.tenant_id AND s.id = r.supplier_id
                WHERE r.status = CAST(? AS recommendation_status)
                """);
        List<Object> args = new ArrayList<>();
        args.add(status);
        if (supplierId != null) {
            sql.append(" AND r.supplier_id = ?");
            args.add(supplierId);
        }
        if (from != null) {
            sql.append(" AND (r.created_at, r.id) < (?, ?)");
            args.add(from.createdAt());
            args.add(from.id());
        }
        sql.append(" ORDER BY r.created_at DESC, r.id DESC LIMIT ?");
        args.add(limit + 1);

        List<RecommendationResponse> rows = jdbc.query(sql.toString(),
                this::mapRecommendation, args.toArray());

        boolean more = rows.size() > limit;
        List<RecommendationResponse> page = more ? rows.subList(0, limit) : rows;
        RecommendationResponse last = page.isEmpty() ? null : page.get(page.size() - 1);
        String nextCursor = more && last != null
                ? new RecommendationCursor(last.createdAt(), last.id()).encode()
                : null;
        return new RecommendationPage(page, nextCursor);
    }

    public boolean dismiss(UUID recommendationId, String reason, UUID actor) {
        int updated = jdbc.update("""
                UPDATE reorder_recommendations
                SET status = 'dismissed',
                    resolved_at = now(),
                    resolved_by = ?,
                    rationale = CASE WHEN CAST(? AS text) IS NULL THEN rationale
                                     ELSE rationale || ' [dismissed: ' || CAST(? AS text) || ']'
                                END
                WHERE id = ? AND status = 'open'
                """, actor, reason, reason, recommendationId);
        return updated > 0;
    }

    private static final String FORECAST_COLUMNS = """
            SELECT f.id, f.product_id, f.location_id, f.method::text AS method, f.generated_at,
                   f.history_days, f.avg_daily_demand, f.demand_stddev, f.horizon_days,
                   f.forecast_qty, f.days_of_cover, f.projected_stockout_on,
                   f.reorder_point, f.service_level, f.confidence
            """;

    private ForecastResponse rescale(ForecastResponse forecast, Integer horizonDays) {
        if (horizonDays == null || horizonDays.equals(forecast.horizonDays())) {
            return forecast;
        }
        BigDecimal quantity = forecast.avgDailyDemand()
                .multiply(BigDecimal.valueOf(horizonDays), DemandSeries.MC)
                .setScale(3, RoundingMode.HALF_UP);
        return new ForecastResponse(forecast.productId(), forecast.locationId(),
                forecast.method(), forecast.generatedAt(), forecast.historyDays(),
                forecast.avgDailyDemand(), forecast.demandStddev(), horizonDays, quantity,
                forecast.daysOfCover(), forecast.projectedStockoutOn(), forecast.reorderPoint(),
                forecast.serviceLevel(), forecast.confidence());
    }

    private ForecastRow mapForecast(ResultSet rs, int rowNum) throws SQLException {
        ForecastResponse forecast = new ForecastResponse(
                rs.getObject("product_id", UUID.class),
                rs.getObject("location_id", UUID.class),
                rs.getString("method"),
                rs.getObject("generated_at", OffsetDateTime.class),
                rs.getInt("history_days"),
                rs.getBigDecimal("avg_daily_demand"),
                rs.getBigDecimal("demand_stddev"),
                rs.getInt("horizon_days"),
                rs.getBigDecimal("forecast_qty"),
                rs.getBigDecimal("days_of_cover"),
                rs.getObject("projected_stockout_on", LocalDate.class),
                rs.getBigDecimal("reorder_point"),
                rs.getBigDecimal("service_level"),
                rs.getBigDecimal("confidence"));
        return new ForecastRow(forecast, rs.getObject("generated_at", OffsetDateTime.class),
                rs.getLong("id"));
    }

    private RecommendationResponse mapRecommendation(ResultSet rs, int rowNum) throws SQLException {
        return new RecommendationResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("product_id", UUID.class),
                rs.getString("sku"),
                rs.getString("name"),
                rs.getObject("location_id", UUID.class),
                rs.getObject("supplier_id", UUID.class),
                rs.getString("supplier_name"),
                rs.getBigDecimal("quantity_on_hand"),
                rs.getBigDecimal("reorder_point"),
                rs.getBigDecimal("recommended_qty"),
                rs.getBigDecimal("estimated_cost"),
                rs.getObject("projected_stockout_on", LocalDate.class),
                rs.getString("urgency"),
                rs.getString("rationale"),
                rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private record ForecastRow(ForecastResponse forecast, OffsetDateTime generatedAt, long id) {
    }

    private record ForecastCursor(OffsetDateTime generatedAt, long id) {

        String encode() {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (generatedAt + "|" + id).getBytes(StandardCharsets.UTF_8));
        }

        static ForecastCursor decode(String encoded) {
            String raw = decodeRaw(encoded);
            if (raw == null) {
                return null;
            }
            try {
                int split = raw.lastIndexOf('|');
                return new ForecastCursor(OffsetDateTime.parse(raw.substring(0, split)),
                        Long.parseLong(raw.substring(split + 1)));
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    private record RecommendationCursor(OffsetDateTime createdAt, UUID id) {

        String encode() {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (createdAt + "|" + id).getBytes(StandardCharsets.UTF_8));
        }

        static RecommendationCursor decode(String encoded) {
            String raw = decodeRaw(encoded);
            if (raw == null) {
                return null;
            }
            try {
                int split = raw.lastIndexOf('|');
                return new RecommendationCursor(OffsetDateTime.parse(raw.substring(0, split)),
                        UUID.fromString(raw.substring(split + 1)));
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    private static String decodeRaw(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
