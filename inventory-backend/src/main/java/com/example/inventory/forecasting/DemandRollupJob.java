package com.example.inventory.forecasting;

import java.time.LocalDate;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.inventory.tenancy.TenantContext;

@Service
public class DemandRollupJob {

    private final JdbcTemplate jdbc;

    public DemandRollupJob(@Qualifier("appDataSource") DataSource appDataSource) {
        this.jdbc = new JdbcTemplate(appDataSource);
    }

    public record RollupResult(int daysWritten) {
    }

    public RollupResult rollUp() {
        return rollUp(null);
    }

    @Transactional
    public RollupResult rollUp(LocalDate through) {
        requireBoundTenant();
        int written = jdbc.update(ROLLUP_SQL, through);
        return new RollupResult(written);
    }

    private void requireBoundTenant() {
        TenantContext.currentTenantId().orElseThrow(() -> new IllegalStateException(
                "DemandRollupJob requires a bound tenant. It deliberately does not bind one "
                        + "itself — see the class Javadoc and CLAUDE.md section 12."));
    }

    private static final String ROLLUP_SQL = """
            WITH params AS (
                SELECT COALESCE(
                           (SELECT timezone FROM tenants WHERE id = current_tenant_id()),
                           'UTC') AS tz
            ),
            bounds AS (
                SELECT tz,
                       COALESCE(CAST(? AS date), (now() AT TIME ZONE tz)::date) AS through
                FROM params
            ),
            mv AS (
                SELECT m.product_id,
                       m.location_id,
                       (m.occurred_at AT TIME ZONE b.tz)::date AS day,
                       m.occurred_at,
                       m.id,
                       m.movement_type,
                       m.quantity_delta,
                       m.reference_id
                FROM stock_movements m
                CROSS JOIN bounds b
                WHERE (m.occurred_at AT TIME ZONE b.tz)::date <= b.through
            ),
            -- Balance after each movement, in business-time order. The tiebreak
            -- on id matters: several movements of one sale share an occurred_at,
            -- and an unstable order would make the within-day minimum wobble.
            running AS (
                SELECT mv.*,
                       SUM(quantity_delta) OVER (
                           PARTITION BY product_id, location_id
                           ORDER BY occurred_at, id
                           ROWS UNBOUNDED PRECEDING) AS balance_after
                FROM mv
            ),
            per_day AS (
                SELECT r.product_id,
                       r.location_id,
                       r.day,
                       SUM(r.quantity_delta)  AS delta,
                       MIN(r.balance_after)   AS min_balance_within,
                       -- Net demand: sales out, restocked returns back in. A
                       -- damaged-goods return is absent from the ledger by
                       -- design (V5) and so is correctly absent from both.
                       SUM(CASE WHEN r.movement_type = 'sale'
                                THEN -r.quantity_delta ELSE 0 END)
                     - SUM(CASE WHEN r.movement_type = 'sale_return'
                                THEN  r.quantity_delta ELSE 0 END) AS net_units,
                       COUNT(DISTINCT CASE WHEN r.movement_type = 'sale'
                                           THEN r.reference_id END) AS sales_count,
                       COALESCE(SUM(
                           CASE WHEN r.movement_type = 'sale'
                                THEN -r.quantity_delta * si.unit_price
                                WHEN r.movement_type = 'sale_return'
                                THEN  r.quantity_delta * si.unit_price * -1
                                ELSE 0 END), 0) AS revenue
                FROM running r
                LEFT JOIN sale_items si
                       ON si.sale_id    = r.reference_id
                      AND si.product_id = r.product_id
                GROUP BY r.product_id, r.location_id, r.day
            ),
            span AS (
                SELECT product_id, location_id, MIN(day) AS first_day
                FROM mv
                GROUP BY product_id, location_id
            ),
            -- The calendar, not the movements. This is what keeps zero-demand
            -- days in the table instead of silently dropping them.
            calendar AS (
                SELECT s.product_id,
                       s.location_id,
                       s.first_day,
                       d::date AS day
                FROM span s
                CROSS JOIN bounds b
                CROSS JOIN LATERAL generate_series(s.first_day, b.through, interval '1 day') d
            ),
            filled AS (
                SELECT c.product_id,
                       c.location_id,
                       c.day,
                       c.first_day,
                       COALESCE(p.delta, 0)       AS delta,
                       p.min_balance_within,
                       COALESCE(p.net_units, 0)   AS net_units,
                       COALESCE(p.sales_count, 0) AS sales_count,
                       COALESCE(p.revenue, 0)     AS revenue
                FROM calendar c
                LEFT JOIN per_day p
                       ON p.product_id  = c.product_id
                      AND p.location_id = c.location_id
                      AND p.day         = c.day
            ),
            balanced AS (
                SELECT f.*,
                       COALESCE(SUM(delta) OVER (
                           PARTITION BY product_id, location_id
                           ORDER BY day
                           ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0) AS opening
                FROM filled f
            )
            INSERT INTO demand_daily
                (tenant_id, product_id, location_id, day,
                 units_sold, sales_count, revenue, had_stockout)
            SELECT current_tenant_id(),
                   product_id,
                   location_id,
                   day,
                   -- Floored: a return landing on a quiet day cannot make
                   -- demand negative, and the column's CHECK says so too.
                   GREATEST(net_units, 0),
                   sales_count,
                   revenue,
                   -- Empty for part of the day: either carried in empty (an
                   -- outage day has no movements at all, because a refused sale
                   -- writes nothing) or driven to zero within the day.
                   (day > first_day AND opening <= 0)
                       OR (min_balance_within IS NOT NULL AND min_balance_within <= 0)
            FROM balanced
            ON CONFLICT (tenant_id, product_id, location_id, day) DO UPDATE
                SET units_sold   = EXCLUDED.units_sold,
                    sales_count  = EXCLUDED.sales_count,
                    revenue      = EXCLUDED.revenue,
                    had_stockout = EXCLUDED.had_stockout
            """;
}
