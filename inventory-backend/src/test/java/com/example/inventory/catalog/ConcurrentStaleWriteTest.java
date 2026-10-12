package com.example.inventory.catalog;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.inventory.AbstractIntegrationTest;
import com.example.inventory.catalog.CatalogDtos.Product;
import com.example.inventory.catalog.CatalogDtos.ProductWriteRequest;
import com.example.inventory.tenancy.TenantContext;
import com.example.inventory.web.PreconditionFailedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Concurrent stale write")
class ConcurrentStaleWriteTest extends AbstractIntegrationTest {

    @Autowired
    private ProductCatalog catalog;

    private static UUID tenantId;
    private static boolean seeded;

    @BeforeEach
    void seedTenant() throws SQLException {
        if (seeded) {
            return;
        }
        tenantId = newTenantId();
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO tenants (id, slug, name) VALUES ('%s', 'etag-%s', 'ETag Ltd')"
                    .formatted(tenantId, tenantId.toString().substring(0, 8)));
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

    private static ProductWriteRequest write(String sku, String name, String price) {
        return new ProductWriteRequest(sku, null, name, null, null, "each",
                new BigDecimal("1.00"), new BigDecimal(price), new BigDecimal("0.15"),
                null, null, null, true, false, null);
    }

    private String readAsOwner(String sql, Object... args) {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            return new JdbcTemplate(new org.springframework.jdbc.datasource
                    .SingleConnectionDataSource(conn, true))
                    .queryForObject(sql, String.class, args);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("two concurrent updates from the same read: one wins, one is refused")
    void concurrentUpdatesFromTheSameReadRejectTheLoser() throws Exception {
        Versioned<Product> original = asTenant(() ->
                catalog.create(write("SKU-ETAG-" + UUID.randomUUID().toString().substring(0, 6),
                        "Original Name", "10.00")));

        String sharedETag = original.etag();
        assertThat(sharedETag).as("a product read must carry a version").isNotBlank();

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();
        AtomicReference<String> winner = new AtomicReference<>();

        CountDownLatch startTogether = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(2);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (String editor : new String[] {"Editor A", "Editor B"}) {
                pool.submit(() -> {
                    try {
                        startTogether.await();
                        asTenant(() -> catalog.update(original.value().id(),
                                write(original.value().sku(), editor, "20.00"), sharedETag));
                        succeeded.incrementAndGet();
                        winner.set(editor);
                    } catch (PreconditionFailedException expected) {
                        refused.incrementAndGet();
                    } catch (Exception e) {
                        unexpected.incrementAndGet();
                    } finally {
                        finished.countDown();
                    }
                    return null;
                });
            }

            startTogether.countDown();
            assertThat(finished.await(30, TimeUnit.SECONDS))
                    .as("both updates should complete; a timeout means a deadlock")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(unexpected.get())
                .as("neither update should fail for an unrelated reason")
                .isZero();
        assertThat(succeeded.get())
                .as("exactly one writer may win")
                .isEqualTo(1);
        assertThat(refused.get())
                .as("THE assertion: the second write must be REFUSED. Asserting only that the "
                        + "first succeeded would pass with no version check at all, because both "
                        + "writes would succeed and nothing would notice.")
                .isEqualTo(1);

        assertThat(readAsOwner("SELECT name FROM products WHERE id = ?", original.value().id()))
                .as("the surviving row must be one editor's work, not a mixture")
                .isEqualTo(winner.get());
    }

    @Test
    @DisplayName("a stale ETag is refused even with no competing writer")
    void aStaleETagIsRefused() throws Exception {
        Versioned<Product> original = asTenant(() ->
                catalog.create(write("SKU-STALE-" + UUID.randomUUID().toString().substring(0, 6),
                        "Before", "10.00")));
        String staleETag = original.etag();

        Versioned<Product> updated = asTenant(() ->
                catalog.update(original.value().id(), write(original.value().sku(), "After", "11.00"), staleETag));
        assertThat(updated.value().name()).isEqualTo("After");

        assertThatThrownBy(() -> asTenant(() ->
                catalog.update(original.value().id(), write(original.value().sku(), "Too Late", "12.00"),
                        staleETag)))
                .isInstanceOf(PreconditionFailedException.class);

        assertThat(readAsOwner("SELECT name FROM products WHERE id = ?", original.value().id()))
                .as("the refused write must not have been applied")
                .isEqualTo("After");
    }

    @Test
    @DisplayName("the ETag changes when the row changes")
    void theETagAdvances() throws Exception {
        Versioned<Product> original = asTenant(() ->
                catalog.create(write("SKU-ADV-" + UUID.randomUUID().toString().substring(0, 6),
                        "First", "10.00")));
        String first = original.etag();

        Versioned<Product> updated = asTenant(() ->
                catalog.update(original.value().id(), write(original.value().sku(), "Second", "11.00"), first));

        assertThat(updated.etag())
                .as("an ETag that never changes cannot detect a stale write")
                .isNotEqualTo(first);
    }

    @Test
    @DisplayName("omitting If-Match is last-write-wins, deliberately")
    void withoutIfMatchTheWriteProceeds() throws Exception {
        Versioned<Product> original = asTenant(() ->
                catalog.create(write("SKU-NOIF-" + UUID.randomUUID().toString().substring(0, 6),
                        "Before", "10.00")));

        asTenant(() -> catalog.update(original.value().id(), write(original.value().sku(), "Once", "11.00"), null));
        Versioned<Product> second = asTenant(() ->
                catalog.update(original.value().id(), write(original.value().sku(), "Twice", "12.00"), null));

        assertThat(second.value().name()).isEqualTo("Twice");
    }

    @Test
    @DisplayName("a malformed If-Match is refused, not ignored")
    void aMalformedIfMatchIsRefused() throws Exception {
        Versioned<Product> original = asTenant(() ->
                catalog.create(write("SKU-BAD-" + UUID.randomUUID().toString().substring(0, 6),
                        "Before", "10.00")));

        assertThatThrownBy(() -> asTenant(() ->
                catalog.update(original.value().id(), write(original.value().sku(), "After", "11.00"),
                        "\"not-an-etag\"")))
                .isInstanceOf(PreconditionFailedException.class);

        assertThat(readAsOwner("SELECT name FROM products WHERE id = ?", original.value().id()))
                .isEqualTo("Before");
    }
}
