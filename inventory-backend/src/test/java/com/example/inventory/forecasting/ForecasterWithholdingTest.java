package com.example.inventory.forecasting;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.inventory.AbstractIntegrationTest;
import com.example.inventory.forecasting.Forecaster.Forecast;
import com.example.inventory.inventory.StockLedgerService;
import com.example.inventory.inventory.StockLedgerService.MovementRequest;
import com.example.inventory.tenancy.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Forecaster withholds a reorder point rather than inventing one")
class ForecasterWithholdingTest extends AbstractIntegrationTest {

    @Autowired
    private StockLedgerService ledger;

    @Autowired
    private DemandRollupJob rollup;

    @Autowired
    private Forecaster forecaster;

    private static UUID tenantId;
    private static UUID locationId;
    private static UUID supplierId;

    private static UUID ready;

    private static UUID readyWithoutSupplier;

    private static UUID tooNew;

    private static UUID zeroDemand;

    private static boolean seeded;

    @BeforeEach
    void seed() throws Exception {
        if (seeded) {
            return;
        }
        tenantId = newTenantId();
        locationId = UUID.randomUUID();
        supplierId = UUID.randomUUID();
        ready = UUID.randomUUID();
        readyWithoutSupplier = UUID.randomUUID();
        tooNew = UUID.randomUUID();
        zeroDemand = UUID.randomUUID();

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate start = today.minusDays(90);

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {

            stmt.execute("""
                    INSERT INTO tenants (id, slug, name, timezone)
                    VALUES ('%s', 'withhold-%s', 'Withholding Fixture', 'UTC')
                    """.formatted(tenantId, tenantId.toString().substring(0, 8)));
            stmt.execute("INSERT INTO locations (id, tenant_id, name, is_default) "
                    + "VALUES ('%s', '%s', 'Shelf', true)".formatted(locationId, tenantId));
            stmt.execute("""
                    INSERT INTO suppliers (id, tenant_id, name, lead_time_days)
                    VALUES ('%s', '%s', 'Fixture Supplies', 7)
                    """.formatted(supplierId, tenantId));

            int n = 0;
            for (UUID productId : new UUID[]{ready, readyWithoutSupplier, tooNew, zeroDemand}) {
                stmt.execute("""
                        INSERT INTO products (id, tenant_id, sku, name, cost_price, selling_price, tax_rate)
                        VALUES ('%s', '%s', 'WH-%s-%d', 'Withholding Product %d', 4.00, 10.00, 0)
                        """.formatted(productId, tenantId,
                        tenantId.toString().substring(0, 8), n, n));
                n++;
            }

            for (UUID productId : new UUID[]{ready, tooNew, zeroDemand}) {
                stmt.execute("""
                        INSERT INTO product_suppliers
                            (tenant_id, product_id, supplier_id, unit_cost, is_preferred)
                        VALUES ('%s', '%s', '%s', 4.00, true)
                        """.formatted(tenantId, productId, supplierId));
            }
        }

        asTenant(() -> {
            for (UUID productId : new UUID[]{ready, readyWithoutSupplier}) {
                stock(productId, start.minusDays(1), 900);
                for (LocalDate d = start; d.isBefore(today); d = d.plusDays(1)) {
                    sell(productId, d, 3);
                }
            }

            stock(tooNew, today.minusDays(7), 100);
            for (LocalDate d = today.minusDays(7); d.isBefore(today); d = d.plusDays(1)) {
                sell(tooNew, d, 3);
            }

            stock(zeroDemand, start, 100);
            return rollup.rollUp(today);
        });
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

    private void stock(UUID productId, LocalDate day, int quantity) {
        ledger.post(new MovementRequest(productId, locationId, "adjustment",
                new BigDecimal(quantity), new BigDecimal("4.00"), null, null, "fixture",
                day.atTime(8, 0).atOffset(ZoneOffset.UTC)));
    }

    private void sell(UUID productId, LocalDate day, int quantity) {
        ledger.post(new MovementRequest(productId, locationId, "sale",
                new BigDecimal(quantity).negate(), new BigDecimal("4.00"), null, null, "fixture",
                day.atTime(12, 0).atOffset(ZoneOffset.UTC)));
    }

    private Forecast forecastFor(UUID productId) throws Exception {
        return asTenant(() -> forecaster.forecast(productId, locationId));
    }

    @Test
    @DisplayName("the positive control: ample history AND a supplier produces a real reorder point")
    void aReadyProductWithASupplierGetsANumber() throws Exception {
        Forecast forecast = forecastFor(ready);

        assertThat(forecast.method())
                .as("90 days of daily sales clears both halves of ADR §5")
                .isNotEqualTo(ForecastMethod.INSUFFICIENT_DATA);
        assertThat(forecast.leadTime())
                .as("and it has a supplier to take a lead time from")
                .isNotNull();
        assertThat(forecast.reorderPoint())
                .as("so there is nothing to withhold. Without this control, every assertion "
                        + "below would pass for a Forecaster that returns null unconditionally.")
                .isNotNull();
        assertThat(forecast.reorderPoint()).isPositive();
    }

    @Test
    @DisplayName("no supplier: withheld, even though the history is ample")
    void aReadyProductWithoutASupplierGetsNoReorderPoint() throws Exception {
        Forecast forecast = forecastFor(readyWithoutSupplier);

        assertThat(forecast.method())
                .as("readiness is satisfied — this product is not being refused for being new")
                .isNotEqualTo(ForecastMethod.INSUFFICIENT_DATA);
        assertThat(forecast.avgDailyDemand())
                .as("and its demand is real — 3 units a day, every day")
                .isPositive();

        assertThat(forecast.leadTime())
                .as("but nothing records who supplies it")
                .isNull();
        assertThat(forecast.reorderPoint())
                .as("so there is no lead time, and a reorder point needs one (ADR §2). "
                        + "Substituting a plausible default here produces a made-up number "
                        + "indistinguishable from a real one — which is precisely the mutation "
                        + "that survived before this test existed.")
                .isNull();
    }

    @Test
    @DisplayName("not ready: withheld on the readiness threshold, with a supplier available")
    void anUnreadyProductGetsNoReorderPointEvenWithASupplier() throws Exception {
        Forecast forecast = forecastFor(tooNew);

        assertThat(forecast.method()).isEqualTo(ForecastMethod.INSUFFICIENT_DATA);
        assertThat(forecast.reorderPoint())
                .as("ADR §5: null, not a confident number from a week of data")
                .isNull();
        assertThat(forecast.projectedStockoutOn()).isNull();

        assertThat(asTenant(() -> forecaster.forecast(tooNew, locationId)))
                .isNotNull();
        assertThat(forecast.selection().daysShortOfHistory())
                .as("and it can say how far short it is, which is what ADR §6's explanation "
                        + "has to quote")
                .isPositive();
    }

    @Test
    @DisplayName("zero measured demand: withheld, with both history and a supplier available")
    void aProductThatNeverSoldGetsNoReorderPoint() throws Exception {
        Forecast forecast = forecastFor(zeroDemand);

        assertThat(forecast.reorderPoint())
                .as("there is nothing to reorder against. A zero reorder point would read as "
                        + "'never restock this', which is a different claim from 'we cannot "
                        + "tell you'.")
                .isNull();
        assertThat(forecast.daysOfCover())
                .as("and cover is effectively infinite, which V1's own comment on the column "
                        + "says is expressed as null")
                .isNull();
    }
}
