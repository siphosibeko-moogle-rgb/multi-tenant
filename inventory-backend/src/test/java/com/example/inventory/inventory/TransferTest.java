package com.example.inventory.inventory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.inventory.AbstractIntegrationTest;
import com.example.inventory.inventory.StockLedgerService.MovementRequest;
import com.example.inventory.inventory.StockLedgerService.TransferRequest;
import com.example.inventory.tenancy.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

@DisplayName("Stock transfers")
class TransferTest extends AbstractIntegrationTest {

    @Autowired
    private StockLedgerService ledger;

    private static UUID tenantId;
    private static UUID otherTenantId;
    private static UUID backRoom;
    private static UUID shopFloor;
    private static UUID otherTenantLocation;
    private static UUID productId;
    private static boolean seeded;

    @BeforeEach
    void seed() throws SQLException {
        if (seeded) {
            return;
        }
        tenantId = newTenantId();
        otherTenantId = newTenantId();
        backRoom = UUID.randomUUID();
        shopFloor = UUID.randomUUID();
        otherTenantLocation = UUID.randomUUID();
        productId = UUID.randomUUID();

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {

            for (UUID tenant : new UUID[] {tenantId, otherTenantId}) {
                stmt.execute("INSERT INTO tenants (id, slug, name) VALUES ('%s', 'xfer-%s', 'Shop')"
                        .formatted(tenant, tenant.toString().substring(0, 8)));
            }
            stmt.execute("INSERT INTO locations (id, tenant_id, name, is_default) "
                    + "VALUES ('%s', '%s', 'Back Room', true)".formatted(backRoom, tenantId));
            stmt.execute("INSERT INTO locations (id, tenant_id, name) "
                    + "VALUES ('%s', '%s', 'Shop Floor')".formatted(shopFloor, tenantId));
            stmt.execute("INSERT INTO locations (id, tenant_id, name, is_default) "
                    + "VALUES ('%s', '%s', 'Their Warehouse', true)"
                    .formatted(otherTenantLocation, otherTenantId));
            stmt.execute("""
                    INSERT INTO products (id, tenant_id, sku, name)
                    VALUES ('%s', '%s', 'SKU-XFER', 'Transferable')
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

    private <T> T readAsOwner(String sql, Class<T> type, Object... args) {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            return new JdbcTemplate(new org.springframework.jdbc.datasource
                    .SingleConnectionDataSource(conn, true)).queryForObject(sql, type, args);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private BigDecimal balanceAt(UUID locationId) {
        return readAsOwner("""
                SELECT COALESCE((SELECT quantity_on_hand FROM product_stock
                                  WHERE product_id = ? AND location_id = ?), 0)
                """, BigDecimal.class, productId, locationId);
    }

    private BigDecimal totalQuantity() {
        return readAsOwner(
                "SELECT COALESCE(SUM(quantity_delta), 0) FROM stock_movements WHERE product_id = ?",
                BigDecimal.class, productId);
    }

    private long movementCount() {
        return readAsOwner("SELECT count(*) FROM stock_movements WHERE product_id = ?",
                Long.class, productId);
    }

    private void stockTheBackRoom(String quantity) throws Exception {
        asTenant(() -> ledger.post(new MovementRequest(productId, backRoom, "purchase_receipt",
                new BigDecimal(quantity), new BigDecimal("5.00"), null, null, "restock", null)));
    }

    @Test
    @DisplayName("moves stock between locations as two paired movements")
    void transferMovesStock() throws Exception {
        stockTheBackRoom("10");

        BigDecimal sourceBefore = balanceAt(backRoom);
        BigDecimal destinationBefore = balanceAt(shopFloor);
        BigDecimal totalBefore = totalQuantity();

        var transfer = asTenant(() -> ledger.transfer(new TransferRequest(
                productId, backRoom, shopFloor, new BigDecimal("4"),
                "to the floor", UUID.randomUUID(), null)));

        assertThat(transfer.out().quantityDelta()).isEqualByComparingTo("-4");
        assertThat(transfer.in().quantityDelta()).isEqualByComparingTo("4");

        assertThat(balanceAt(backRoom))
                .as("source debited by exactly the transferred quantity")
                .isEqualByComparingTo(sourceBefore.subtract(new BigDecimal("4")));
        assertThat(balanceAt(shopFloor))
                .as("destination credited by exactly the transferred quantity")
                .isEqualByComparingTo(destinationBefore.add(new BigDecimal("4")));

        assertThat(totalQuantity())
                .as("a transfer moves stock, it does not create or destroy it")
                .isEqualByComparingTo(totalBefore);

        assertThat(readAsOwner("""
                SELECT count(DISTINCT reference_id) FROM stock_movements
                WHERE product_id = ? AND reference_type = 'transfer'
                """, Long.class, productId))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("a destination that does not exist leaves the source untouched")
    void destinationFailureLeavesSourceUntouched() throws Exception {
        stockTheBackRoom("10");

        BigDecimal sourceBefore = balanceAt(backRoom);
        long movementsBefore = movementCount();
        UUID nowhere = UUID.randomUUID();

        Throwable thrown = catchThrowable(() -> asTenant(() -> ledger.transfer(
                new TransferRequest(productId, backRoom, nowhere, new BigDecimal("4"),
                        "into the void", UUID.randomUUID(), null))));

        assertThat(thrown).as("the transfer must fail").isNotNull();

        assertThat(balanceAt(backRoom))
                .as("the source must be EXACTLY as it was. If this is lower, the transfer_out "
                        + "committed without its transfer_in and that stock no longer exists "
                        + "anywhere — and T4 means there is no update that can put it back.")
                .isEqualByComparingTo(sourceBefore);

        assertThat(movementCount())
                .as("and no movement row may survive — a balance check alone would pass if both "
                        + "movements were written and happened to net out")
                .isEqualTo(movementsBefore);

        assertThat(thrown.getClass().getSimpleName() + ": " + thrown.getMessage())
                .as("the failure mode, for comparison with the cross-tenant case")
                .isNotBlank();
    }

    @Test
    @DisplayName("a destination in another tenant is impossible, and leaves the source untouched")
    void crossTenantDestinationIsImpossible() throws Exception {
        stockTheBackRoom("10");

        BigDecimal sourceBefore = balanceAt(backRoom);
        long movementsBefore = movementCount();

        assertThat(readAsOwner("SELECT count(*) FROM locations WHERE id = ?",
                Long.class, otherTenantLocation))
                .as("precondition: the other tenant's location must really exist, or this test "
                        + "is just the nonexistent-destination case wearing a different name")
                .isEqualTo(1L);

        Throwable thrown = catchThrowable(() -> asTenant(() -> ledger.transfer(
                new TransferRequest(productId, backRoom, otherTenantLocation, new BigDecimal("4"),
                        "stealing", UUID.randomUUID(), null))));

        assertThat(thrown).as("stock must not be transferable out of the tenant").isNotNull();

        assertThat(balanceAt(backRoom))
                .as("the source must be untouched")
                .isEqualByComparingTo(sourceBefore);
        assertThat(movementCount())
                .as("and nothing may be written")
                .isEqualTo(movementsBefore);

        assertThat(readAsOwner("""
                SELECT count(*) FROM stock_movements WHERE location_id = ?
                """, Long.class, otherTenantLocation))
                .as("no movement may reference another tenant's location")
                .isZero();
        assertThat(readAsOwner("""
                SELECT count(*) FROM product_stock WHERE location_id = ?
                """, Long.class, otherTenantLocation))
                .as("nor may a balance appear there")
                .isZero();
    }

    @Test
    @DisplayName("the two destination failures are the same mechanism, demonstrated not assumed")
    void bothDestinationFailuresAreTheSameMechanism() throws Exception {
        stockTheBackRoom("10");

        Throwable nonexistent = catchThrowable(() -> asTenant(() -> ledger.transfer(
                new TransferRequest(productId, backRoom, UUID.randomUUID(), BigDecimal.ONE,
                        null, UUID.randomUUID(), null))));

        Throwable otherTenant = catchThrowable(() -> asTenant(() -> ledger.transfer(
                new TransferRequest(productId, backRoom, otherTenantLocation, BigDecimal.ONE,
                        null, UUID.randomUUID(), null))));

        assertThat(otherTenant)
                .as("a hidden row and an absent row must be indistinguishable, or the error "
                        + "message itself would confirm the other location exists")
                .hasSameClassAs(nonexistent);

        assertThat(com.example.inventory.web.SqlStates.of(otherTenant))
                .as("both are foreign key violations, not a special cross-tenant check")
                .isEqualTo(com.example.inventory.web.SqlStates.of(nonexistent));
    }

    @Test
    @DisplayName("transferring more than the source holds is refused, and writes nothing")
    void transferringMoreThanAvailableIsRefused() throws Exception {
        stockTheBackRoom("10");
        long movementsBefore = movementCount();
        BigDecimal floorBefore = balanceAt(shopFloor);

        assertThatThrownBy(() -> asTenant(() -> ledger.transfer(
                new TransferRequest(productId, backRoom, shopFloor, new BigDecimal("999"),
                        "wishful", UUID.randomUUID(), null))))
                .isInstanceOf(InsufficientStockException.class);

        assertThat(movementCount()).isEqualTo(movementsBefore);
        assertThat(balanceAt(shopFloor))
                .as("the destination must not have been credited by a transfer that failed")
                .isEqualByComparingTo(floorBefore);
    }

    @Test
    @DisplayName("a transfer to the same location is refused")
    void sameLocationTransferIsRefused() throws Exception {
        stockTheBackRoom("10");
        long movementsBefore = movementCount();

        assertThatThrownBy(() -> asTenant(() -> ledger.transfer(
                new TransferRequest(productId, backRoom, backRoom, BigDecimal.ONE,
                        "pointless", UUID.randomUUID(), null))))
                .isInstanceOf(com.example.inventory.web.ConflictException.class);

        assertThat(movementCount())
                .as("two movements netting to zero is not a transfer, it is noise in the ledger")
                .isEqualTo(movementsBefore);
    }
}
