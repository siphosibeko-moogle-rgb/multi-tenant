package com.example.inventory.reporting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.example.inventory.reporting.ReportDtos.DashboardSummary;
import com.example.inventory.reporting.ReportDtos.InventoryValuation;
import com.example.inventory.reporting.ReportDtos.SalesBucket;
import com.example.inventory.reporting.ReportDtos.TopProduct;
import com.example.inventory.reporting.ReportDtos.TopProductSummary;
import com.example.inventory.reporting.ReportDtos.TrendPoint;

@Service
public class ReportQueries {

    static final int TREND_DAYS = 14;

    static final int DASHBOARD_TOP_PRODUCTS = 5;

    static final int TOP_PRODUCTS_DEFAULT_DAYS = 30;

    private final JdbcTemplate jdbc;

    public ReportQueries(@Qualifier("appDataSource") DataSource appDataSource) {
        this.jdbc = new JdbcTemplate(appDataSource);
    }

    private static final String ZONE_CTE = """
            zone AS (
                SELECT COALESCE(
                           (SELECT timezone FROM tenants WHERE id = current_tenant_id()),
                           'UTC') AS tz
            )
            """;

    private static final String ROLLING_BOUNDS_CTE = """
            bounds AS (
                SELECT z.tz,
                       (now() AT TIME ZONE z.tz)::date - (CAST(? AS int) - 1) AS first_day,
                       (now() AT TIME ZONE z.tz)::date                        AS last_day,
                       (((now() AT TIME ZONE z.tz)::date - (CAST(? AS int) - 1))::timestamp
                            AT TIME ZONE z.tz)                                AS start_ts,
                       (((now() AT TIME ZONE z.tz)::date + 1)::timestamp
                            AT TIME ZONE z.tz)                                AS end_ts
                FROM zone z
            )
            """;

    private static final String EXPLICIT_BOUNDS_CTE = """
            bounds AS (
                SELECT z.tz,
                       CAST(? AS date)                                        AS first_day,
                       CAST(? AS date)                                        AS last_day,
                       (CAST(? AS date)::timestamp AT TIME ZONE z.tz)         AS start_ts,
                       ((CAST(? AS date) + 1)::timestamp AT TIME ZONE z.tz)   AS end_ts
                FROM zone z
            )
            """;

    private static final String NET_LINES = """
            in_window AS (
                SELECT s.id,
                       s.location_id,
                       s.discount_amount,
                       (s.sold_at AT TIME ZONE b.tz)::date AS local_day
                FROM sales s
                CROSS JOIN bounds b
                WHERE s.status <> 'voided'
                  AND s.sold_at >= b.start_ts
                  AND s.sold_at <  b.end_ts
            ),
            priced AS (
                SELECT w.id AS sale_id,
                       w.local_day,
                       w.location_id,
                       w.discount_amount,
                       si.product_id,
                       si.quantity,
                       si.unit_cost,
                       si.line_total,
                       SUM(si.line_total) OVER (PARTITION BY w.id) AS sale_lines_total,
                       COALESCE((SELECT SUM(sr.quantity)
                                   FROM sale_returns sr
                                  WHERE sr.sale_id = w.id
                                    AND sr.product_id = si.product_id), 0) AS returned_qty
                FROM in_window w
                JOIN sale_items si ON si.sale_id = w.id
            ),
            net_lines AS (
                SELECT p.sale_id,
                       p.local_day,
                       p.location_id,
                       p.product_id,
                       (p.quantity - p.returned_qty) AS net_quantity,
                       round((p.line_total
                              - COALESCE(p.discount_amount * p.line_total
                                         / NULLIF(p.sale_lines_total, 0), 0))
                             * (p.quantity - p.returned_qty) / p.quantity, 2) AS net_revenue,
                       round(COALESCE(p.unit_cost, 0)
                             * (p.quantity - p.returned_qty), 2)              AS net_cost
                FROM priced p
            )
            """;

    public DashboardSummary dashboard(String period, int periodDays) {
        SalesAggregate sales = salesAggregate(periodDays);

        return new DashboardSummary(
                period,
                sales.revenue(),
                sales.count(),
                sales.grossProfit(),
                currentInventoryCostValue(),
                stockStateCount("reorder"),
                stockStateCount("out_of_stock"),
                openRecommendationCount(),
                salesTrend(),
                dashboardTopProducts(periodDays));
    }

    private record SalesAggregate(BigDecimal revenue, long count, BigDecimal grossProfit) {
    }

    private static final String SALES_AGGREGATE_SQL = "WITH " + ZONE_CTE + ","
            + ROLLING_BOUNDS_CTE + "," + NET_LINES + """
            SELECT COALESCE(SUM(n.net_revenue), 0)                  AS revenue,
                   COALESCE(SUM(n.net_revenue - n.net_cost), 0)     AS gross_profit,
                   (SELECT count(*) FROM in_window)                 AS sales_count
            FROM net_lines n
            """;

    private SalesAggregate salesAggregate(int days) {
        return jdbc.queryForObject(SALES_AGGREGATE_SQL,
                (rs, n) -> new SalesAggregate(
                        money(rs.getBigDecimal("revenue")),
                        rs.getLong("sales_count"),
                        money(rs.getBigDecimal("gross_profit"))),
                days, days);
    }

    private static final String CURRENT_COST_VALUE_SQL = """
            SELECT COALESCE(round(SUM(ps.quantity_on_hand * p.cost_price), 2), 0) AS value
            FROM product_stock ps
            JOIN products p ON p.id = ps.product_id
            WHERE p.deleted_at IS NULL AND p.is_active AND p.is_tracked
            """;

    private BigDecimal currentInventoryCostValue() {
        return money(jdbc.queryForObject(CURRENT_COST_VALUE_SQL, BigDecimal.class));
    }

    private long stockStateCount(String state) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM v_stock_status WHERE stock_state = ?",
                Long.class, state);
        return count == null ? 0L : count;
    }

    private long openRecommendationCount() {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM reorder_recommendations WHERE status = 'open'",
                Long.class);
        return count == null ? 0L : count;
    }

    private static final String TREND_SQL = "WITH " + ZONE_CTE + ","
            + ROLLING_BOUNDS_CTE + "," + NET_LINES + """
            , calendar AS (
                SELECT generate_series(b.first_day, b.last_day, interval '1 day')::date AS day
                FROM bounds b
            )
            SELECT c.day, COALESCE(SUM(n.net_revenue), 0) AS revenue
            FROM calendar c
            LEFT JOIN net_lines n ON n.local_day = c.day
            GROUP BY c.day
            ORDER BY c.day
            """;

    private List<TrendPoint> salesTrend() {
        return jdbc.query(TREND_SQL,
                (rs, n) -> new TrendPoint(
                        rs.getObject("day", LocalDate.class),
                        money(rs.getBigDecimal("revenue"))),
                TREND_DAYS, TREND_DAYS);
    }

    private static final String DASHBOARD_TOP_SQL = "WITH " + ZONE_CTE + ","
            + ROLLING_BOUNDS_CTE + "," + NET_LINES + """
            SELECT n.product_id,
                   p.name,
                   SUM(n.net_quantity) AS units_sold,
                   SUM(n.net_revenue)  AS revenue
            FROM net_lines n
            JOIN products p ON p.id = n.product_id
            GROUP BY n.product_id, p.name
            HAVING SUM(n.net_quantity) > 0
            ORDER BY units_sold DESC, revenue DESC, n.product_id
            LIMIT ?
            """;

    private List<TopProductSummary> dashboardTopProducts(int days) {
        return jdbc.query(DASHBOARD_TOP_SQL,
                (rs, n) -> new TopProductSummary(
                        rs.getObject("product_id", UUID.class),
                        rs.getString("name"),
                        quantity(rs.getBigDecimal("units_sold"))),
                days, days, DASHBOARD_TOP_PRODUCTS);
    }

    public List<SalesBucket> salesSummary(LocalDate from, LocalDate to, String groupBy) {
        String unit = switch (groupBy) {
            case "day", "week", "month" -> groupBy;
            default -> throw new IllegalArgumentException("unvalidated groupBy: " + groupBy);
        };

        String sql = "WITH " + ZONE_CTE + "," + EXPLICIT_BOUNDS_CTE + "," + NET_LINES + """
                , calendar AS (
                    SELECT generate_series(
                               date_trunc('%1$s', b.first_day::timestamp),
                               date_trunc('%1$s', b.last_day::timestamp),
                               interval '1 %1$s')::date AS period_start
                    FROM bounds b
                ),
                bucketed AS (
                    SELECT date_trunc('%1$s', n.local_day::timestamp)::date AS period_start,
                           n.net_quantity,
                           n.net_revenue,
                           n.net_cost
                    FROM net_lines n
                )
                SELECT c.period_start,
                       COALESCE(SUM(k.net_quantity), 0)                AS units_sold,
                       COALESCE(SUM(k.net_revenue), 0)                 AS revenue,
                       COALESCE(SUM(k.net_revenue - k.net_cost), 0)    AS gross_profit
                FROM calendar c
                LEFT JOIN bucketed k ON k.period_start = c.period_start
                GROUP BY c.period_start
                ORDER BY c.period_start
                """.formatted(unit);

        return jdbc.query(sql,
                (rs, n) -> new SalesBucket(
                        rs.getObject("period_start", LocalDate.class),
                        quantity(rs.getBigDecimal("units_sold")),
                        money(rs.getBigDecimal("revenue")),
                        money(rs.getBigDecimal("gross_profit"))),
                from, to, from, to);
    }

    public InventoryValuation inventoryValuation(UUID locationId, LocalDate asOf, boolean replay) {
        String filter = locationId == null ? "" : "  AND %s.location_id = ?\n";
        List<Object> args = new ArrayList<>();
        args.add(asOf);

        String perProduct;
        if (replay) {
            perProduct = """
                    per_product AS (
                        SELECT m.product_id, SUM(m.quantity_delta) AS qty
                        FROM stock_movements m
                        CROSS JOIN bounds b
                        WHERE m.occurred_at < b.end_ts
                    """ + filter.formatted("m") + """
                        GROUP BY m.product_id
                    )
                    """;
        } else {
            perProduct = """
                    per_product AS (
                        SELECT ps.product_id, SUM(ps.quantity_on_hand) AS qty
                        FROM product_stock ps
                        WHERE true
                    """ + filter.formatted("ps") + """
                        GROUP BY ps.product_id
                    )
                    """;
        }
        if (locationId != null) {
            args.add(locationId);
        }

        String sql = "WITH " + ZONE_CTE + "," + """
                bounds AS (
                    SELECT z.tz,
                           ((CAST(? AS date) + 1)::timestamp AT TIME ZONE z.tz) AS end_ts
                    FROM zone z
                ),
                """ + perProduct + """
                SELECT COALESCE(round(SUM(pp.qty * p.cost_price), 2), 0)    AS cost_value,
                       COALESCE(round(SUM(pp.qty * p.selling_price), 2), 0) AS retail_value,
                       count(*) FILTER (WHERE pp.qty <> 0)                  AS product_count
                FROM per_product pp
                JOIN products p ON p.id = pp.product_id
                WHERE p.deleted_at IS NULL AND p.is_active AND p.is_tracked
                """;

        return jdbc.queryForObject(sql,
                (rs, n) -> new InventoryValuation(
                        money(rs.getBigDecimal("cost_value")),
                        money(rs.getBigDecimal("retail_value")),
                        rs.getLong("product_count"),
                        asOf),
                args.toArray());
    }

    public LocalDate todayForTenant() {
        return jdbc.queryForObject("WITH " + ZONE_CTE + """
                SELECT (now() AT TIME ZONE z.tz)::date AS today FROM zone z
                """, LocalDate.class);
    }

    public List<TopProduct> topProducts(LocalDate from, LocalDate to, String order, int limit) {
        boolean best = switch (order) {
            case "best" -> true;
            case "worst" -> false;
            default -> throw new IllegalArgumentException("unvalidated order: " + order);
        };

        String sql = "WITH " + ZONE_CTE + "," + EXPLICIT_BOUNDS_CTE + "," + NET_LINES + """
                , agg AS (
                    SELECT n.product_id,
                           SUM(n.net_quantity) AS units_sold,
                           SUM(n.net_revenue)  AS revenue,
                           SUM(n.net_cost)     AS cost
                    FROM net_lines n
                    GROUP BY n.product_id
                )
                SELECT p.id AS product_id,
                       p.sku,
                       p.name,
                       COALESCE(a.units_sold, 0) AS units_sold,
                       COALESCE(a.revenue, 0)    AS revenue,
                       CASE WHEN COALESCE(a.revenue, 0) = 0 THEN NULL
                            ELSE round((a.revenue - a.cost) * 100 / a.revenue, 2)
                       END AS gross_margin_pct
                FROM products p
                LEFT JOIN agg a ON a.product_id = p.id
                WHERE p.deleted_at IS NULL AND p.is_active
                """
                + (best
                        ? "  AND COALESCE(a.units_sold, 0) > 0\n"
                          + "ORDER BY units_sold DESC, revenue DESC, p.id\n"
                        : "  AND p.is_tracked\n"
                          + "ORDER BY units_sold ASC, revenue ASC, p.id\n")
                + "LIMIT ?";

        return jdbc.query(sql,
                (rs, n) -> new TopProduct(
                        rs.getObject("product_id", UUID.class),
                        rs.getString("sku"),
                        rs.getString("name"),
                        quantity(rs.getBigDecimal("units_sold")),
                        money(rs.getBigDecimal("revenue")),
                        rs.getBigDecimal("gross_margin_pct")),
                from, to, from, to, limit);
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal quantity(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(3, RoundingMode.HALF_UP);
    }
}
