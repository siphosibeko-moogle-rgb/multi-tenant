package com.example.inventory.inventory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.inventory.AbstractIntegrationTest;
import com.example.inventory.inventory.StockLedgerService.MovementRequest;
import com.example.inventory.tenancy.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Concurrent oversell")
class ConcurrentOversellTest extends AbstractIntegrationTest {

    private static final int UNITS_IN_STOCK = 10;
    private static final int CONCURRENT_SALES = 20;

    @Autowired
    private StockLedgerService ledger;

    @Autowired
    @Qualifier("appDataSource")
    private DataSource appDataSource;

    private static UUID tenantId;
    private static UUID productId;
    private static UUID locationId;
    private static boolean seeded;

    @BeforeEach
    void seedProduct() throws SQLException {
        if (seeded) {
            return;
        }
        tenantId = newTenantId();
        productId = UUID.randomUUID();
        locationId = UUID.randomUUID();

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {

            stmt.execute("INSERT INTO tenants (id, slug, name) VALUES ('%s', 'oversell-%s', 'Oversell Ltd')"
                    .formatted(tenantId, tenantId.toString().substring(0, 8)));
            stmt.execute("INSERT INTO locations (id, tenant_id, name, is_default) "
                    + "VALUES ('%s', '%s', 'Main', true)".formatted(locationId, tenantId));
            stmt.execute("""
                    INSERT INTO products (id, tenant_id, sku, name, allow_negative_stock)
                    VALUES ('%s', '%s', 'SKU-OVERSELL', 'Last Ten Units', false)
                    """.formatted(productId, tenantId));
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

    private JdbcTemplate ownerJdbc(Connection conn) {
        return new JdbcTemplate(
                new org.springframework.jdbc.datasource.SingleConnectionDataSource(conn, true));
    }

    private <T> T readAsOwner(String sql, Class<T> type, Object... args) {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            return ownerJdbc(conn).queryForObject(sql, type, args);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("20 concurrent sales of a 10-unit product: 10 sold, 10 rejected, never negative")
    void twentyConcurrentSalesOfTenUnits() throws Exception {
        asTenant(() -> ledger.post(new MovementRequest(
                productId, locationId, "purchase_receipt",
                new BigDecimal(UNITS_IN_STOCK), new BigDecimal("10.00"),
                null, null, "opening", null)));

        assertThat(readAsOwner("SELECT quantity_on_hand FROM product_stock WHERE product_id = ?",
                BigDecimal.class, productId))
                .as("precondition: exactly %d units, or the arithmetic below means nothing",
                        UNITS_IN_STOCK)
                .isEqualByComparingTo(new BigDecimal(UNITS_IN_STOCK));

        AtomicInteger sold = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        CountDownLatch startTogether = new CountDownLatch(1);
        CountDownLatch allFinished = new CountDownLatch(CONCURRENT_SALES);

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_SALES);
        try {
            for (int i = 0; i < CONCURRENT_SALES; i++) {
                pool.submit(() -> {
                    try {
                        startTogether.await();
                        asTenant(() -> ledger.post(new MovementRequest(
                                productId, locationId, "sale",
                                BigDecimal.ONE.negate(), null,
                                "sale", UUID.randomUUID(), null, null)));
                        sold.incrementAndGet();
                    } catch (InsufficientStockException expected) {
                        rejected.incrementAndGet();
                    } catch (Exception e) {
                        unexpected.incrementAndGet();
                    } finally {
                        allFinished.countDown();
                    }
                    return null;
                });
            }

            startTogether.countDown();
            assertThat(allFinished.await(60, TimeUnit.SECONDS))
                    .as("all %d attempts should finish; a timeout here means a deadlock",
                            CONCURRENT_SALES)
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(unexpected.get())
                .as("no attempt should fail for a reason other than insufficient stock")
                .isZero();
        assertThat(sold.get())
                .as("exactly %d sales may succeed — one per unit that existed", UNITS_IN_STOCK)
                .isEqualTo(UNITS_IN_STOCK);
        assertThat(rejected.get())
                .as("and the other %d must be refused", CONCURRENT_SALES - UNITS_IN_STOCK)
                .isEqualTo(CONCURRENT_SALES - UNITS_IN_STOCK);

        BigDecimal finalBalance = readAsOwner(
                "SELECT quantity_on_hand FROM product_stock WHERE product_id = ?",
                BigDecimal.class, productId);

        assertThat(finalBalance)
                .as("the balance must be exactly zero — 10 in, 10 out")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(finalBalance.signum())
                .as("NEVER negative. A negative balance here is stock sold that did not exist.")
                .isNotNegative();

        BigDecimal rebuilt = readAsOwner("""
                SELECT COALESCE(SUM(quantity_delta), 0) FROM stock_movements WHERE product_id = ?
                """, BigDecimal.class, productId);
        assertThat(rebuilt)
                .as("SUM(quantity_delta) must equal the cached balance")
                .isEqualByComparingTo(finalBalance);

        assertThat(readAsOwner("SELECT count(*) FROM stock_movements WHERE product_id = ?",
                Long.class, productId))
                .as("one receipt plus exactly %d sales — a rejected sale must leave NO row",
                        UNITS_IN_STOCK)
                .isEqualTo(UNITS_IN_STOCK + 1L);
    }

    @Test
    @DisplayName("a rejected sale writes nothing at all")
    void aRejectedSaleIsNotPartiallyApplied() throws Exception {
        UUID otherProduct = UUID.randomUUID();
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    INSERT INTO products (id, tenant_id, sku, name, allow_negative_stock)
                    VALUES ('%s', '%s', 'SKU-EMPTY', 'Never Stocked', false)
                    """.formatted(otherProduct, tenantId));
        }

        try {
            asTenant(() -> ledger.post(new MovementRequest(
                    otherProduct, locationId, "sale", BigDecimal.ONE.negate(),
                    null, null, null, null, null)));
            throw new AssertionError("expected the sale to be refused");
        } catch (InsufficientStockException expected) {
            assertThat(expected.productId()).isEqualTo(otherProduct);
            assertThat(expected.requested()).isEqualByComparingTo(BigDecimal.ONE);
        }

        assertThat(readAsOwner("SELECT count(*) FROM stock_movements WHERE product_id = ?",
                Long.class, otherProduct))
                .as("the refused movement must not be in the ledger")
                .isZero();

        assertThat(readAsOwner("SELECT count(*) FROM product_stock WHERE product_id = ?",
                Long.class, otherProduct))
                .as("and must leave no cached balance behind either")
                .isZero();
    }

    @Test
    @DisplayName("a product that allows negative stock is not blocked")
    void allowNegativeStockIsHonoured() throws Exception {
        UUID backorderable = UUID.randomUUID();
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    INSERT INTO products (id, tenant_id, sku, name, allow_negative_stock)
                    VALUES ('%s', '%s', 'SKU-BACKORDER', 'Backorderable', true)
                    """.formatted(backorderable, tenantId));
        }

        var posted = asTenant(() -> ledger.post(new MovementRequest(
                backorderable, locationId, "sale", BigDecimal.ONE.negate(),
                null, null, null, null, null)));

        assertThat(posted.balanceAfter())
                .as("allow_negative_stock means exactly that")
                .isEqualByComparingTo(new BigDecimal("-1"));
    }

    @Test
    @DisplayName("the ledger is append-only, even to the application role")
    void theLedgerCannotBeRewritten() {
        JdbcTemplate appJdbc = new JdbcTemplate(appDataSource);

        List<String> statements = List.of(
                "UPDATE stock_movements SET quantity_delta = 999 WHERE product_id = '%s'"
                        .formatted(productId),
                "DELETE FROM stock_movements WHERE product_id = '%s'".formatted(productId));

        for (String sql : statements) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> appJdbc.update(sql))
                    .as("the ledger must not be rewritable: %s", sql)
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
    }
}
