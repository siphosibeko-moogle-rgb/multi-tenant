package com.example.inventory.seed;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.example.inventory.auth.AuthDtos.AuthTokens;
import com.example.inventory.auth.AuthDtos.TenantRegistrationRequest;
import com.example.inventory.auth.TenantRegistrationService;
import com.example.inventory.catalog.CatalogDtos.CategoryWriteRequest;
import com.example.inventory.catalog.CatalogDtos.LocationWriteRequest;
import com.example.inventory.catalog.CatalogDtos.ProductWriteRequest;
import com.example.inventory.catalog.ProductCatalog;
import com.example.inventory.inventory.InsufficientStockException;
import com.example.inventory.inventory.StockLedgerService;
import com.example.inventory.inventory.StockLedgerService.MovementRequest;
import com.example.inventory.purchasing.GoodsReceiptService;
import com.example.inventory.purchasing.PurchaseOrderDtos.PurchaseOrderLineRequest;
import com.example.inventory.purchasing.PurchaseOrderDtos.PurchaseOrderWriteRequest;
import com.example.inventory.purchasing.PurchaseOrderDtos.ReceiptLine;
import com.example.inventory.purchasing.PurchaseOrderDtos.ReceiptRequest;
import com.example.inventory.purchasing.PurchaseOrderService;
import com.example.inventory.sales.SaleService;
import com.example.inventory.sales.SalesDtos.SaleLineRequest;
import com.example.inventory.sales.SalesDtos.SaleWriteRequest;
import com.example.inventory.tenancy.TenantContext;

@Component
public class TenantSeeder {

    private final TenantRegistrationService registration;
    private final ProductCatalog catalog;
    private final SaleService sales;
    private final StockLedgerService ledger;
    private final PurchaseOrderService purchaseOrders;
    private final GoodsReceiptService goodsReceipts;
    private final JdbcTemplate jdbc;

    public TenantSeeder(TenantRegistrationService registration,
                        ProductCatalog catalog,
                        SaleService sales,
                        StockLedgerService ledger,
                        PurchaseOrderService purchaseOrders,
                        GoodsReceiptService goodsReceipts,
                        @Qualifier("appDataSource") DataSource appDataSource) {
        this.registration = registration;
        this.catalog = catalog;
        this.sales = sales;
        this.ledger = ledger;
        this.purchaseOrders = purchaseOrders;
        this.goodsReceipts = goodsReceipts;
        this.jdbc = new JdbcTemplate(appDataSource);
    }

    public record SeededTenant(
            UUID tenantId,
            UUID ownerId,
            UUID locationId,
            UUID supplierId,
            Map<String, UUID> productIdsByShape) {
    }

    public SeededTenant seedTenant(String businessName, String slug, long randomSeed, int windowWeeks) {
        LocalDate today = LocalDate.now();
        LocalDate windowStart = today.minusWeeks(windowWeeks);
        Random rng = new Random(randomSeed);

        AuthTokens tokens = registration.register(new TenantRegistrationRequest(
                businessName, slug, "owner+" + slug + "@example.test", "SeedPassw0rd!1",
                "Seed Owner", null, null));
        UUID tenantId = tokens.user().tenant().id();
        UUID ownerId = tokens.user().id();

        TenantContext.bind(new TenantContext.TenantIdentity(tenantId, ownerId, "owner"));
        try {
            UUID locationId = catalog.createLocation(
                    new LocationWriteRequest("Main Store", null, true)).id();
            UUID categoryId = catalog.createCategory(
                    new CategoryWriteRequest("Bakery", null)).id();

            UUID steadyId = createProduct(slug + "-steady", "Steady Seller Bread", categoryId);
            UUID intermittentId = createProduct(slug + "-inter", "Intermittent Spice Mix", categoryId);
            UUID stockoutId = createProduct(slug + "-stockout", "Popular Croissant", categoryId);
            UUID trendingId = createProduct(slug + "-trend", "Trending Cold Brew", categoryId);
            UUID deadId = createProduct(slug + "-dead", "Discontinued Fruitcake", categoryId);
            UUID seasonalId = createProduct(slug + "-seasonal", "Weekend Party Platter", categoryId);
            UUID newId = createProduct(slug + "-new", "Brand New Sourdough", categoryId);

            UUID supplierId = createSupplier(tenantId, "Golden Wheat Supplies " + slug, 7);

            seedSteadySeller(steadyId, locationId, windowStart, today, new Random(rng.nextLong()));
            seedIntermittentSeller(intermittentId, locationId, windowStart, today, new Random(rng.nextLong()));
            seedStockoutProduct(stockoutId, supplierId, locationId, windowStart, today, new Random(rng.nextLong()));
            seedTrendingProduct(trendingId, locationId, windowStart, today, new Random(rng.nextLong()));
            seedDeadStock(deadId, locationId, windowStart);
            seedSeasonalProduct(seasonalId, locationId, windowStart, today, new Random(rng.nextLong()));
            seedBrandNew(newId, locationId, today, new Random(rng.nextLong()));

            seedSupplierHistory(supplierId, locationId, List.of(steadyId, intermittentId, trendingId),
                    windowStart, today, new Random(rng.nextLong()));

            UUID newSupplierId = createSupplier(tenantId, "Riverbend Provisions " + slug, 21);
            seedSupplierHistory(newSupplierId, locationId, List.of(seasonalId),
                    windowStart, today, new Random(rng.nextLong()), 2);

            linkProductToSupplier(tenantId, steadyId, supplierId, new BigDecimal("8.00"));
            linkProductToSupplier(tenantId, intermittentId, supplierId, new BigDecimal("12.00"));
            linkProductToSupplier(tenantId, stockoutId, supplierId, new BigDecimal("7.00"));
            linkProductToSupplier(tenantId, trendingId, supplierId, new BigDecimal("6.00"));
            linkProductToSupplier(tenantId, seasonalId, newSupplierId, new BigDecimal("5.00"));

            Map<String, UUID> shapes = new LinkedHashMap<>();
            shapes.put("steady", steadyId);
            shapes.put("intermittent", intermittentId);
            shapes.put("stockout", stockoutId);
            shapes.put("trending", trendingId);
            shapes.put("dead", deadId);
            shapes.put("seasonal", seasonalId);
            shapes.put("new", newId);

            return new SeededTenant(tenantId, ownerId, locationId, supplierId, shapes);
        } finally {
            TenantContext.clear();
        }
    }

    void seedSteadySeller(UUID productId, UUID locationId, LocalDate start, LocalDate endExclusive, Random rng) {
        stockUp(productId, locationId, start.minusDays(1), new BigDecimal(200), new BigDecimal("8.00"));
        for (LocalDate date = start; date.isBefore(endExclusive); date = date.plusDays(1)) {
            restockIfLow(productId, locationId, date, new BigDecimal("8.00"));
            if (rng.nextDouble() < 0.90) {
                sell(productId, locationId, date, 2 + rng.nextInt(3));
            }
        }
    }

    void seedIntermittentSeller(UUID productId, UUID locationId, LocalDate start, LocalDate endExclusive,
                                Random rng) {
        stockUp(productId, locationId, start.minusDays(1), new BigDecimal(60), new BigDecimal("12.00"));
        for (LocalDate date = start; date.isBefore(endExclusive); date = date.plusDays(1)) {
            restockIfLow(productId, locationId, date, new BigDecimal("12.00"));
            if (rng.nextDouble() < 0.10) {
                sell(productId, locationId, date, 1 + rng.nextInt(3));
            }
        }
    }

    void seedTrendingProduct(UUID productId, UUID locationId, LocalDate start, LocalDate endExclusive, Random rng) {
        stockUp(productId, locationId, start.minusDays(1), new BigDecimal(250), new BigDecimal("6.00"));
        for (LocalDate date = start; date.isBefore(endExclusive); date = date.plusDays(1)) {
            restockIfLow(productId, locationId, date, new BigDecimal("6.00"));
            double weekIndex = ChronoUnit.DAYS.between(start, date) / 7.0;
            double weeklyTarget = 5.0 + weekIndex * 0.6;
            double dailyProbability = Math.min(0.85, weeklyTarget / 7.0 / 2.0);
            if (rng.nextDouble() < dailyProbability) {
                int qty = Math.max(1, (int) Math.round(weeklyTarget / 7.0 * (0.7 + rng.nextDouble() * 0.6)));
                sell(productId, locationId, date, qty);
            }
        }
    }

    void seedDeadStock(UUID productId, UUID locationId, LocalDate openingDate) {
        stockUp(productId, locationId, openingDate, new BigDecimal(15), new BigDecimal("20.00"));
    }

    void seedSeasonalProduct(UUID productId, UUID locationId, LocalDate start,
                             LocalDate endExclusive, Random rng) {
        stockUp(productId, locationId, start.minusDays(1), new BigDecimal(120), new BigDecimal("5.00"));
        for (LocalDate date = start; date.isBefore(endExclusive); date = date.plusDays(1)) {
            restockIfLow(productId, locationId, date, new BigDecimal("5.00"));

            DayOfWeek dayOfWeek = date.getDayOfWeek();
            boolean weekend = dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY;

            if (weekend) {
                sell(productId, locationId, date, 8 + rng.nextInt(3));
            } else if (rng.nextDouble() < 0.7) {
                sell(productId, locationId, date, 1 + rng.nextInt(2));
            }
        }
    }

    void seedBrandNew(UUID productId, UUID locationId, LocalDate today, Random rng) {
        LocalDate start = today.minusDays(10);
        stockUp(productId, locationId, start, new BigDecimal(30), new BigDecimal("9.00"));
        for (LocalDate date = start; date.isBefore(today); date = date.plusDays(1)) {
            if (rng.nextDouble() < 0.4) {
                sell(productId, locationId, date, 1 + rng.nextInt(2));
            }
        }
    }

    void seedStockoutProduct(UUID productId, UUID supplierId, UUID locationId, LocalDate start,
                             LocalDate endExclusive, Random rng) {
        long totalDays = ChronoUnit.DAYS.between(start, endExclusive);
        LocalDate stockoutStart = start.plusDays(totalDays * 4 / 10);

        stockUp(productId, locationId, start.minusDays(1), new BigDecimal(40), new BigDecimal("7.00"));

        LocalDate date = start;
        while (date.isBefore(stockoutStart)) {
            if (rng.nextDouble() < 0.6) {
                sellUpTo(productId, locationId, date, 2 + rng.nextInt(3));
            }
            date = date.plusDays(1);
        }

        BigDecimal remaining = currentStock(productId, locationId);
        if (remaining.signum() > 0) {
            sell(productId, locationId, stockoutStart, remaining.intValue());
        }

        int stockoutDays = 5;
        for (int i = 0; i < stockoutDays; i++) {
            LocalDate refusedDate = stockoutStart.plusDays(i);
            boolean refused = attemptSaleExpectingRefusal(productId, locationId, refusedDate, 2);
            if (!refused) {
                throw new IllegalStateException(
                        "Seed data bug: a sale succeeded during the intended stockout window for "
                                + productId + " on " + refusedDate + " — the shelf was not actually empty.");
            }
        }

        LocalDate restockDate = stockoutStart.plusDays(stockoutDays);
        orderAndReceive(supplierId, locationId, productId, new BigDecimal(35), new BigDecimal("7.00"),
                restockDate.minusDays(4), 4);
        for (LocalDate d = restockDate.plusDays(1); d.isBefore(endExclusive); d = d.plusDays(1)) {
            restockIfLow(productId, locationId, d, new BigDecimal("7.00"));
            if (rng.nextDouble() < 0.5) {
                sell(productId, locationId, d, 2 + rng.nextInt(2));
            }
        }
    }

    void seedSupplierHistory(UUID supplierId, UUID locationId, List<UUID> productIds,
                             LocalDate windowStart, LocalDate today, Random rng) {
        seedSupplierHistory(supplierId, locationId, productIds, windowStart, today, rng, 6);
    }

    void seedSupplierHistory(UUID supplierId, UUID locationId, List<UUID> productIds,
                             LocalDate windowStart, LocalDate today, Random rng, int receiptCount) {
        long totalDays = ChronoUnit.DAYS.between(windowStart, today);

        for (int i = 0; i < receiptCount; i++) {
            UUID productId = productIds.get(i % productIds.size());
            LocalDate orderedDate = windowStart.plusDays(totalDays * i / receiptCount);
            int leadDays = 3 + rng.nextInt(6);
            BigDecimal quantity = new BigDecimal(40 + rng.nextInt(20));
            orderAndReceive(supplierId, locationId, productId, quantity, new BigDecimal("8.00"),
                    orderedDate, leadDays);
        }
    }

    private void orderAndReceive(UUID supplierId, UUID locationId, UUID productId, BigDecimal quantity,
                                 BigDecimal unitCost, LocalDate orderedDate, int leadDays) {
        OffsetDateTime orderedAt = orderedDate.atTime(9, 0).atOffset(ZoneOffset.UTC);
        OffsetDateTime receivedAt = orderedAt.plusDays(leadDays);

        var created = purchaseOrders.create(new PurchaseOrderWriteRequest(
                supplierId, locationId, null, "Seed restock", null,
                List.of(new PurchaseOrderLineRequest(productId, quantity, unitCost))));
        UUID poId = created.id();

        purchaseOrders.submit(poId);
        jdbc.update("UPDATE purchase_orders SET ordered_at = ? WHERE id = ?",
                java.sql.Timestamp.from(orderedAt.toInstant()), poId);

        goodsReceipts.receive(poId, new ReceiptRequest(receivedAt,
                List.of(new ReceiptLine(productId, quantity, unitCost))));
    }

    private UUID createSupplier(UUID tenantId, String name, int promisedLeadTimeDays) {
        UUID supplierId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO suppliers (id, tenant_id, name, lead_time_days)
                VALUES (?, ?, ?, ?)
                """, supplierId, tenantId, name, promisedLeadTimeDays);
        return supplierId;
    }

    private void linkProductToSupplier(UUID tenantId, UUID productId, UUID supplierId,
                                       BigDecimal unitCost) {
        jdbc.update("""
                INSERT INTO product_suppliers
                    (tenant_id, product_id, supplier_id, unit_cost, is_preferred)
                VALUES (?, ?, ?, ?, true)
                """, tenantId, productId, supplierId, unitCost);
    }

    private UUID createProduct(String sku, String name, UUID categoryId) {
        catalog.create(new ProductWriteRequest(
                sku, null, name, null, categoryId, "each",
                new BigDecimal("8.00"), new BigDecimal("15.00"), new BigDecimal("0.15"),
                null, null, null, true, false, null));
        return jdbc.queryForObject("SELECT id FROM products WHERE sku = ?", UUID.class, sku);
    }

    private void sell(UUID productId, UUID locationId, LocalDate date, int quantity) {
        sales.record(new SaleWriteRequest(
                List.of(new SaleLineRequest(productId, new BigDecimal(quantity), null, null)),
                null, locationId, null, null, null, null,
                date.atTime(12, 0).atOffset(ZoneOffset.UTC)));
    }

    private void sellUpTo(UUID productId, UUID locationId, LocalDate date, int wanted) {
        int available = currentStock(productId, locationId).intValue();
        int actual = Math.min(wanted, available);
        if (actual > 0) {
            sell(productId, locationId, date, actual);
        }
    }

    private boolean attemptSaleExpectingRefusal(UUID productId, UUID locationId, LocalDate date, int quantity) {
        try {
            sell(productId, locationId, date, quantity);
            return false;
        } catch (InsufficientStockException e) {
            return true;
        }
    }

    private void stockUp(UUID productId, UUID locationId, LocalDate date, BigDecimal quantity,
                         BigDecimal unitCost) {
        ledger.post(new MovementRequest(productId, locationId, "adjustment", quantity, unitCost,
                null, null, "seed opening stock", date.atTime(8, 0).atOffset(ZoneOffset.UTC)));
    }

    private void restockIfLow(UUID productId, UUID locationId, LocalDate date, BigDecimal unitCost) {
        if (currentStock(productId, locationId).compareTo(new BigDecimal(30)) < 0) {
            stockUp(productId, locationId, date, new BigDecimal(150), unitCost);
        }
    }

    private BigDecimal currentStock(UUID productId, UUID locationId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(quantity_on_hand), 0) FROM product_stock
                WHERE product_id = ? AND location_id = ?
                """, BigDecimal.class, productId, locationId);
    }
}
