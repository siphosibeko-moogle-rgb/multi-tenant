package com.example.inventory.forecasting;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.inventory.forecasting.DemandSeries.Day;

@Repository
public class DemandSeriesRepository {

    private final JdbcTemplate jdbc;
    private final ForecastingProperties properties;

    public DemandSeriesRepository(@Qualifier("appDataSource") DataSource appDataSource,
                                  ForecastingProperties properties) {
        this.jdbc = new JdbcTemplate(appDataSource);
        this.properties = properties;
    }

    private int windowDays() {
        return properties.historyWindowDays();
    }

    public DemandSeries load(UUID productId, UUID locationId) {
        List<Day> days = jdbc.query("""
                SELECT day, units_sold, had_stockout
                FROM demand_daily
                WHERE product_id = ? AND location_id = ?
                  AND day > (
                      SELECT max(day) FROM demand_daily
                      WHERE product_id = ? AND location_id = ?
                  ) - CAST(? AS integer)
                ORDER BY day
                """,
                (rs, rowNum) -> new Day(
                        rs.getObject("day", java.time.LocalDate.class),
                        rs.getBigDecimal("units_sold"),
                        rs.getBoolean("had_stockout")),
                productId, locationId, productId, locationId, windowDays());
        return new DemandSeries(productId, locationId, days);
    }

    public List<DemandSeries> loadAll() {
        record Row(UUID productId, UUID locationId, Day day) {
        }
        List<Row> rows = jdbc.query("""
                WITH windowed AS (
                    SELECT product_id, location_id, day, units_sold, had_stockout,
                           max(day) OVER (PARTITION BY product_id, location_id) AS last_day
                    FROM demand_daily
                )
                SELECT product_id, location_id, day, units_sold, had_stockout
                FROM windowed
                WHERE day > last_day - CAST(? AS integer)
                ORDER BY product_id, location_id, day
                """,
                (rs, rowNum) -> new Row(
                        rs.getObject("product_id", UUID.class),
                        rs.getObject("location_id", UUID.class),
                        new Day(rs.getObject("day", java.time.LocalDate.class),
                                rs.getBigDecimal("units_sold"),
                                rs.getBoolean("had_stockout"))),
                windowDays());

        List<DemandSeries> series = new ArrayList<>();
        UUID currentProduct = null;
        UUID currentLocation = null;
        List<Day> current = new ArrayList<>();
        for (Row row : rows) {
            boolean sameSeries = row.productId().equals(currentProduct)
                    && row.locationId().equals(currentLocation);
            if (!sameSeries) {
                if (currentProduct != null) {
                    series.add(new DemandSeries(currentProduct, currentLocation, List.copyOf(current)));
                }
                currentProduct = row.productId();
                currentLocation = row.locationId();
                current = new ArrayList<>();
            }
            current.add(row.day());
        }
        if (currentProduct != null) {
            series.add(new DemandSeries(currentProduct, currentLocation, List.copyOf(current)));
        }
        return series;
    }

    public BigDecimal quantityOnHand(UUID productId, UUID locationId) {
        BigDecimal onHand = jdbc.queryForObject("""
                SELECT COALESCE(sum(quantity_on_hand), 0) FROM product_stock
                WHERE product_id = ? AND location_id = ?
                """, BigDecimal.class, productId, locationId);
        return onHand == null ? BigDecimal.ZERO : onHand;
    }
}
