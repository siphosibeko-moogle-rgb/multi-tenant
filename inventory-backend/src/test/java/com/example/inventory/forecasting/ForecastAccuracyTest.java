package com.example.inventory.forecasting;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.inventory.AbstractIntegrationTest;
import com.example.inventory.tenancy.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Forecast accuracy against the naive baseline")
class ForecastAccuracyTest extends AbstractIntegrationTest {

    @Autowired
    private ForecastAccuracyJob accuracy;

    @Autowired
    @Qualifier("appDataSource")
    private javax.sql.DataSource appDataSource;

    private static UUID tenantId;
    private static UUID locationId;
    private static boolean seeded;

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 1);

    private static final int HORIZON = 10;

    private static final LocalDate PRIOR_START = LocalDate.of(2026, 5, 12);

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 5, 22);

    @BeforeEach
    void seed() throws Exception {
        if (seeded) {
            return;
        }
        tenantId = newTenantId();
        locationId = UUID.randomUUID();

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    INSERT INTO tenants (id, slug, name, timezone)
                    VALUES ('%s', 'acc-%s', 'Accuracy Fixture', 'UTC')
                    """.formatted(tenantId, tenantId.toString().substring(0, 8)));
            stmt.execute("INSERT INTO locations (id, tenant_id, name, is_default) "
                    + "VALUES ('%s', '%s', 'Shelf', true)".formatted(locationId, tenantId));
        }
        seeded = true;
    }

    private <T> T asTenant(Callable<T> work) throws Exception {
        TenantContext.bind(new TenantContext.TenantIdentity(tenantId, null, null));
        try {
            return work.call();
        } finally {
            TenantContext.clear();
        }
    }

    private JdbcTemplate owner() {
        try {
            Connection conn = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
            return new JdbcTemplate(new org.springframework.jdbc.datasource
                    .SingleConnectionDataSource(conn, true));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID fixture(String sku, ForecastMethod method, int priorDaily, int actualDaily,
                         String predictedTotal) {
        UUID productId = UUID.randomUUID();
        JdbcTemplate owner = owner();
        owner.update("""
                INSERT INTO products (id, tenant_id, sku, name, cost_price, selling_price, tax_rate)
                VALUES (?, ?, ?, ?, 4.00, 10.00, 0)
                """, productId, tenantId, sku, sku);

        for (int i = 0; i < HORIZON; i++) {
            insertDemand(owner, productId, PRIOR_START.plusDays(i), priorDaily);
            insertDemand(owner, productId, PERIOD_START.plusDays(i), actualDaily);
        }

        owner.update("""
                INSERT INTO forecasts
                    (tenant_id, product_id, location_id, method, generated_at, history_days,
                     avg_daily_demand, demand_stddev, horizon_days, forecast_qty,
                     service_level, is_current)
                VALUES (?, ?, ?, CAST(? AS forecast_method), ?, 90, ?, 0, ?, ?, 0.950, true)
                """,
                tenantId, productId, locationId, method.dbValue(),
                java.sql.Timestamp.valueOf(PERIOD_START.minusDays(1).atTime(9, 0)),
                new BigDecimal(predictedTotal).divide(BigDecimal.valueOf(HORIZON),
                        java.math.MathContext.DECIMAL64),
                HORIZON, new BigDecimal(predictedTotal));
        return productId;
    }

    private void insertDemand(JdbcTemplate owner, UUID productId, LocalDate day, int units) {
        owner.update("""
                INSERT INTO demand_daily
                    (tenant_id, product_id, location_id, day, units_sold, sales_count, revenue)
                VALUES (?, ?, ?, ?, ?, 1, 0)
                """, tenantId, productId, locationId, day, new BigDecimal(units));
    }

    private List<Map<String, Object>> scoresFor(UUID productId) {
        return owner().queryForList("""
                SELECT f.method::text AS method, a.predicted_qty, a.actual_qty,
                       a.abs_pct_error, a.period_start, a.period_end
                FROM forecast_accuracy a
                JOIN forecasts f ON f.id = a.forecast_id
                WHERE a.product_id = ?
                ORDER BY f.method::text
                """, productId);
    }

    @Nested
    @DisplayName("scoring")
    class Scoring {

        @Test
        @DisplayName("a real forecast and a naive baseline are both scored, over the same period")
        void bothSidesAreScored() throws Exception {
            UUID productId = fixture("ACC-1", ForecastMethod.MOVING_AVERAGE, 3, 5, "45");

            asTenant(() -> accuracy.scoreDueForecasts(TODAY));

            List<Map<String, Object>> scores = scoresFor(productId);
            assertThat(scores)
                    .as("ADR §7: the baseline is scored from day one, in the same pass — not "
                            + "added later once a real model looks good enough to survive it")
                    .hasSize(2);

            Map<String, Object> real = scores.stream()
                    .filter(s -> s.get("method").equals("moving_average")).findFirst().orElseThrow();
            Map<String, Object> naive = scores.stream()
                    .filter(s -> s.get("method").equals("naive")).findFirst().orElseThrow();

            assertThat((BigDecimal) real.get("actual_qty")).isEqualByComparingTo("50");
            assertThat((BigDecimal) real.get("predicted_qty")).isEqualByComparingTo("45");
            assertThat((BigDecimal) real.get("abs_pct_error")).isEqualByComparingTo("10.0000");

            assertThat((BigDecimal) naive.get("predicted_qty"))
                    .as("'same as last period' is exactly the previous window's actual total — "
                            + "no averaging, no trend, no seasonality")
                    .isEqualByComparingTo("30");
            assertThat((BigDecimal) naive.get("actual_qty"))
                    .as("scored against the SAME actual, or the comparison is meaningless")
                    .isEqualByComparingTo("50");
            assertThat((BigDecimal) naive.get("abs_pct_error")).isEqualByComparingTo("40.0000");
        }

        @Test
        @DisplayName("the naive row is a forecast row carrying method 'naive' — ADR §7 shape (a)")
        void theBaselineIsAForecastRow() throws Exception {
            UUID productId = fixture("ACC-2", ForecastMethod.CROSTON, 4, 4, "40");
            asTenant(() -> accuracy.scoreDueForecasts(TODAY));

            List<Map<String, Object>> naiveRows = owner().queryForList("""
                    SELECT method::text AS method, is_current, forecast_qty
                    FROM forecasts WHERE product_id = ? AND method = 'naive'
                    """, productId);

            assertThat(naiveRows).hasSize(1);
            assertThat((Boolean) naiveRows.get(0).get("is_current"))
                    .as("never current: a synthetic baseline must not collide with "
                            + "forecasts_current_uq or be mistaken for a product's live forecast")
                    .isFalse();
            assertThat((BigDecimal) naiveRows.get(0).get("forecast_qty"))
                    .isEqualByComparingTo("40");
        }

        @Test
        @DisplayName("nothing is scored before its period has fully elapsed")
        void anUnfinishedPeriodIsNotScored() throws Exception {
            UUID productId = fixture("ACC-3", ForecastMethod.MOVING_AVERAGE, 3, 5, "45");

            asTenant(() -> accuracy.scoreDueForecasts(PERIOD_START.plusDays(HORIZON - 1)));
            assertThat(scoresFor(productId))
                    .as("scoring a half-finished period compares a full-horizon prediction "
                            + "against a partial actual, and reports every method as wildly "
                            + "over-forecasting — a bug that looks like a finding")
                    .isEmpty();

            asTenant(() -> accuracy.scoreDueForecasts(PERIOD_START.plusDays(HORIZON)));
            assertThat(scoresFor(productId))
                    .as("without this, a job that scored nothing at all would satisfy the "
                            + "assertion above perfectly")
                    .hasSize(2);
        }

        @Test
        @DisplayName("re-running scores nothing twice")
        void scoringIsIdempotent() throws Exception {
            UUID productId = fixture("ACC-4", ForecastMethod.MOVING_AVERAGE, 3, 5, "45");

            asTenant(() -> accuracy.scoreDueForecasts(TODAY));
            asTenant(() -> accuracy.scoreDueForecasts(TODAY));
            asTenant(() -> accuracy.scoreDueForecasts(TODAY));

            assertThat(scoresFor(productId))
                    .as("duplicate scores do not error and do not look wrong in any single "
                            + "row — they silently reweight every aggregate built on top")
                    .hasSize(2);
        }

        @Test
        @DisplayName("a duplicate insert is refused by the database, not merely avoided in Java")
        void theUniquenessIsEnforcedBySchema() throws Exception {
            UUID productId = fixture("ACC-5", ForecastMethod.MOVING_AVERAGE, 3, 5, "45");
            asTenant(() -> accuracy.scoreDueForecasts(TODAY));

            Long forecastId = owner().queryForObject(
                    "SELECT forecast_id FROM forecast_accuracy WHERE product_id = ? LIMIT 1",
                    Long.class, productId);

            assertThatThrownBy(() -> owner().update("""
                    INSERT INTO forecast_accuracy
                        (tenant_id, forecast_id, product_id, period_start, period_end,
                         predicted_qty, actual_qty, abs_pct_error)
                    VALUES (?, ?, ?, ?, ?, 1, 1, 0)
                    """, tenantId, forecastId, productId, PERIOD_START,
                    PERIOD_START.plusDays(HORIZON)))
                    .as("forecast_accuracy_period_uq")
                    .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        }

        @Test
        @DisplayName("insufficient_data forecasts are not scored — there was no prediction")
        void unreadyForecastsAreNotScored() throws Exception {
            UUID productId = fixture("ACC-6", ForecastMethod.INSUFFICIENT_DATA, 3, 5, "0");
            asTenant(() -> accuracy.scoreDueForecasts(TODAY));

            assertThat(scoresFor(productId))
                    .as("scoring a withheld forecast as if it had predicted zero would put a "
                            + "100% error on every new product and drag down the average for "
                            + "whatever method it eventually gets")
                    .isEmpty();
        }

        @Test
        @DisplayName("with no tenant bound the job refuses rather than scoring nothing quietly")
        void anUnboundRunIsRefused() {
            TenantContext.clear();
            assertThatThrownBy(() -> accuracy.scoreDueForecasts(TODAY))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("bound tenant");
        }
    }

    @Nested
    @DisplayName("the percentage error")
    class PercentageError {

        @Test
        @DisplayName("a zero actual leaves the error null, not a huge finite number")
        void mapeIsUndefinedAgainstZero() throws Exception {
            UUID productId = fixture("ACC-7", ForecastMethod.CROSTON, 4, 0, "40");
            asTenant(() -> accuracy.scoreDueForecasts(TODAY));

            List<Map<String, Object>> scores = scoresFor(productId);
            assertThat(scores).hasSize(2);
            for (Map<String, Object> score : scores) {
                assertThat((BigDecimal) score.get("actual_qty")).isEqualByComparingTo("0");
                assertThat(score.get("abs_pct_error"))
                        .as("the relative error against zero is infinite however small the "
                                + "prediction. A large finite stand-in would dominate every "
                                + "average it entered, and intermittent products produce zero "
                                + "periods routinely — the very bucket §7 wants compared.")
                        .isNull();
            }
        }

        @Test
        @DisplayName("a perfect forecast scores zero error")
        void anExactPredictionScoresZero() throws Exception {
            UUID productId = fixture("ACC-8", ForecastMethod.MOVING_AVERAGE, 2, 5, "50");
            asTenant(() -> accuracy.scoreDueForecasts(TODAY));

            Map<String, Object> real = scoresFor(productId).stream()
                    .filter(s -> s.get("method").equals("moving_average")).findFirst().orElseThrow();
            assertThat((BigDecimal) real.get("abs_pct_error")).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("over- and under-forecasting by the same margin score the same")
        void theErrorIsAbsolute() throws Exception {
            UUID over = fixture("ACC-9", ForecastMethod.MOVING_AVERAGE, 5, 5, "60");
            UUID under = fixture("ACC-10", ForecastMethod.MOVING_AVERAGE, 5, 5, "40");
            asTenant(() -> accuracy.scoreDueForecasts(TODAY));

            BigDecimal overError = (BigDecimal) scoresFor(over).stream()
                    .filter(s -> s.get("method").equals("moving_average"))
                    .findFirst().orElseThrow().get("abs_pct_error");
            BigDecimal underError = (BigDecimal) scoresFor(under).stream()
                    .filter(s -> s.get("method").equals("moving_average"))
                    .findFirst().orElseThrow().get("abs_pct_error");

            assertThat(overError).isEqualByComparingTo("20.0000");
            assertThat(underError)
                    .as("MAPE is symmetric — this is the 'absolute' in the name, and a signed "
                            + "version would let over- and under-forecasts cancel to a "
                            + "flattering zero")
                    .isEqualByComparingTo(overError);
        }
    }

    @Nested
    @DisplayName("the per-bucket comparison")
    class PerBucket {

        @Test
        @DisplayName("accuracy is reported per method, so naive can be compared against each")
        void resultsAreGroupedByMethod() throws Exception {
            fixture("ACC-11", ForecastMethod.MOVING_AVERAGE, 2, 5, "50");
            fixture("ACC-12", ForecastMethod.CROSTON, 5, 5, "20");
            asTenant(() -> accuracy.scoreDueForecasts(TODAY));

            List<ForecastAccuracyJob.MethodAccuracy> byMethod =
                    asTenant(() -> accuracy.accuracyByMethod());
            System.out.println("\naccuracy by method:");
            byMethod.forEach(m -> System.out.printf("  %-24s %d evaluations, MAPE %s%n",
                    m.method().dbValue(), m.evaluations(), m.meanAbsPctError()));

            assertThat(byMethod.stream().map(ForecastAccuracyJob.MethodAccuracy::method))
                    .as("ADR §7: the comparison must be visible per demand-shape bucket, not "
                            + "only as one averaged number — a method that beats naive for "
                            + "steady sellers and loses for intermittent ones is a real "
                            + "finding, and one average hides exactly it")
                    .contains(ForecastMethod.MOVING_AVERAGE, ForecastMethod.CROSTON,
                            ForecastMethod.NAIVE);

            ForecastAccuracyJob.MethodAccuracy movingAverage = byMethod.stream()
                    .filter(m -> m.method() == ForecastMethod.MOVING_AVERAGE)
                    .findFirst().orElseThrow();
            ForecastAccuracyJob.MethodAccuracy naive = byMethod.stream()
                    .filter(m -> m.method() == ForecastMethod.NAIVE)
                    .findFirst().orElseThrow();

            assertThat(movingAverage.meanAbsPctError())
                    .as("this fixture's moving_average forecast was exact (50 predicted, 50 "
                            + "actual) while naive quoted the prior period's 20 — so the "
                            + "comparison must come out in the real method's favour, and a "
                            + "grouping that mixed them could not show it. NOTE this is a "
                            + "statement about the GROUPING, not about moving_average: the "
                            + "numbers are hand-picked. See the class Javadoc, and "
                            + "BaselineComparisonTest for the comparison that judges methods.")
                    .isLessThan(naive.meanAbsPctError());
        }
    }
}
