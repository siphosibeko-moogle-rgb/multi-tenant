package com.example.inventory.forecasting;

import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.inventory.AbstractIntegrationTest;
import com.example.inventory.seed.TenantSeeder;
import com.example.inventory.seed.TenantSeeder.SeededTenant;
import com.example.inventory.tenancy.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("A recompute rolls up first")
class RecomputeRunsTheRollupTest extends AbstractIntegrationTest {

    @Autowired
    private TenantSeeder seeder;

    @Autowired
    private ReorderService reorderService;

    @Autowired
    @Qualifier("appDataSource")
    private javax.sql.DataSource appDataSource;

    private SeededTenant tenant;

    @BeforeEach
    void seed() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        tenant = seeder.seedTenant("Rollup Wiring " + tag, "wiring-" + tag, 1L, 10);
    }

    private <T> T asTenant(Callable<T> work) throws Exception {
        TenantContext.bind(new TenantContext.TenantIdentity(
                tenant.tenantId(), tenant.ownerId(), "owner"));
        try {
            return work.call();
        } finally {
            TenantContext.clear();
        }
    }

    private long count(String table) throws Exception {
        return asTenant(() -> new JdbcTemplate(appDataSource).queryForObject(
                "SELECT count(*) FROM " + table, Long.class));
    }

    @Test
    @DisplayName("recomputing populates demand_daily, not just forecasts")
    void recomputeRefreshesTheDemandDataItForecastsFrom() throws Exception {
        assertThat(count("demand_daily"))
                .as("fixture check: nothing has rolled up yet, so the table this recompute "
                        + "reads from is empty. If this is ever non-zero, something in the "
                        + "fixture has started rolling up and the test has stopped testing "
                        + "the wiring.")
                .isZero();

        ReorderService.RecomputeResult result = asTenant(() -> reorderService.recomputeAll());

        assertThat(count("demand_daily"))
                .as("the ledger holds 10 weeks of seeded history for seven products, so a "
                        + "recompute that refreshed the demand data has rows here. Zero means "
                        + "the pipeline started at stage two — which errors nowhere and simply "
                        + "reports every product as insufficient_data forever.")
                .isGreaterThan(0);

        assertThat(result.forecastsWritten())
                .as("and a forecast per seeded product/location, from data this call produced "
                        + "itself rather than data a test happened to lay down first")
                .isEqualTo(7);

        assertThat(count("forecasts"))
                .as("persisted, not merely counted in the result")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("recomputing twice is safe — the rollup underneath is idempotent")
    void asecondRecomputeDoesNotDoubleTheDemandRows() throws Exception {
        asTenant(() -> reorderService.recomputeAll());
        long afterFirst = count("demand_daily");

        asTenant(() -> reorderService.recomputeAll());

        assertThat(count("demand_daily"))
                .as("DemandRollupJob upserts, so running it on every recompute must not "
                        + "accumulate — otherwise the fix for the empty-table bug would trade "
                        + "it for a growing one")
                .isEqualTo(afterFirst);
    }
}
