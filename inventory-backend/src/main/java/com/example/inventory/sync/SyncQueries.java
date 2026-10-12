package com.example.inventory.sync;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.example.inventory.catalog.ProductCatalog;
import com.example.inventory.inventory.InventoryQueries;
import com.example.inventory.suppliers.SupplierService;
import com.example.inventory.sync.SyncDtos.SyncChanges;
import com.example.inventory.sync.SyncDtos.Tombstone;

@Service
public class SyncQueries {

    static final String PRODUCT = "product";
    static final String CATEGORY = "category";
    static final String SUPPLIER = "supplier";
    static final String LOCATION = "location";
    static final String STOCK = "stock";

    private final JdbcTemplate jdbc;
    private final ProductCatalog catalog;
    private final SupplierService suppliers;
    private final InventoryQueries inventory;

    public SyncQueries(@Qualifier("appDataSource") DataSource appDataSource,
                       ProductCatalog catalog,
                       SupplierService suppliers,
                       InventoryQueries inventory) {
        this.jdbc = new JdbcTemplate(appDataSource);
        this.catalog = catalog;
        this.suppliers = suppliers;
        this.inventory = inventory;
    }

    private record Change(String entityType, UUID entityId, boolean deleted,
                          long xactId, long seq) {
    }

    public SyncChanges changesSince(SyncToken since, int limit) {
        long boundary = safeBoundary();

        List<Change> rows = jdbc.query("""
                SELECT entity_type, entity_id, deleted, xact_id, seq
                FROM change_log
                WHERE (xact_id, seq) > (?, ?)
                  AND xact_id < ?
                ORDER BY xact_id, seq
                LIMIT ?
                """, (rs, i) -> new Change(
                        rs.getString("entity_type"),
                        rs.getObject("entity_id", UUID.class),
                        rs.getBoolean("deleted"),
                        rs.getLong("xact_id"),
                        rs.getLong("seq")),
                since.xactId(), since.seq(), boundary, limit + 1);

        boolean hasMore = rows.size() > limit;
        List<Change> page = hasMore ? rows.subList(0, limit) : rows;

        SyncToken next = hasMore
                ? new SyncToken(page.get(page.size() - 1).xactId(),
                                page.get(page.size() - 1).seq())
                : new SyncToken(boundary, 0L);

        return hydrate(page, next, hasMore);
    }

    private long safeBoundary() {
        Long boundary = jdbc.queryForObject(
                "SELECT pg_snapshot_xmin(pg_current_snapshot())::text::bigint", Long.class);
        return boundary == null ? 0L : boundary;
    }

    private SyncChanges hydrate(List<Change> page, SyncToken next, boolean hasMore) {
        Map<String, List<UUID>> live = new LinkedHashMap<>();
        List<Tombstone> deleted = new ArrayList<>();

        for (Change change : page) {
            if (change.deleted()) {
                deleted.add(new Tombstone(change.entityType(), change.entityId()));
            } else {
                live.computeIfAbsent(change.entityType(), k -> new ArrayList<>())
                        .add(change.entityId());
            }
        }

        List<UUID> productIds = live.getOrDefault(PRODUCT, List.of());
        List<UUID> categoryIds = live.getOrDefault(CATEGORY, List.of());
        List<UUID> supplierIds = live.getOrDefault(SUPPLIER, List.of());
        List<UUID> locationIds = live.getOrDefault(LOCATION, List.of());
        List<UUID> stockProductIds = live.getOrDefault(STOCK, List.of());

        var products = catalog.findAllByIds(productIds);
        var categories = catalog.findCategoriesByIds(categoryIds);
        var locations = catalog.findLocationsByIds(locationIds);
        var supplierRows = suppliers.findAllByIds(supplierIds);
        var stock = inventory.stockForProducts(stockProductIds);

        addMissingAsTombstones(deleted, PRODUCT, productIds,
                products.stream().map(p -> p.id()).toList());
        addMissingAsTombstones(deleted, CATEGORY, categoryIds,
                categories.stream().map(c -> c.id()).toList());
        addMissingAsTombstones(deleted, SUPPLIER, supplierIds,
                supplierRows.stream().map(s -> s.id()).toList());
        addMissingAsTombstones(deleted, LOCATION, locationIds,
                locations.stream().map(l -> l.id()).toList());

        return new SyncChanges(next.encode(), hasMore, products, categories,
                supplierRows, locations, stock, deleted);
    }

    private static void addMissingAsTombstones(List<Tombstone> into, String entityType,
                                               List<UUID> requested, List<UUID> found) {
        for (UUID id : requested) {
            if (!found.contains(id)) {
                into.add(new Tombstone(entityType, id));
            }
        }
    }
}
