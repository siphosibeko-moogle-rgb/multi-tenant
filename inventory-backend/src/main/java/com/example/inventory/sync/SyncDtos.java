package com.example.inventory.sync;

import java.util.List;
import java.util.UUID;

import com.example.inventory.catalog.CatalogDtos.Category;
import com.example.inventory.catalog.CatalogDtos.Location;
import com.example.inventory.catalog.CatalogDtos.Product;
import com.example.inventory.inventory.InventoryDtos.StockStatus;
import com.example.inventory.suppliers.SupplierDtos.Supplier;

public final class SyncDtos {

    private SyncDtos() {
    }

    public record SyncChanges(
            String syncToken,
            boolean hasMore,
            List<Product> products,
            List<Category> categories,
            List<Supplier> suppliers,
            List<Location> locations,
            List<StockStatus> stock,
            List<Tombstone> deleted) {
    }

    public record Tombstone(String entityType, UUID id) {
    }
}
