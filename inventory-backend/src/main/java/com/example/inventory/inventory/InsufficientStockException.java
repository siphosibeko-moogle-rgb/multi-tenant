package com.example.inventory.inventory;

import java.math.BigDecimal;
import java.util.UUID;

public class InsufficientStockException extends RuntimeException {

    private final UUID productId;
    private final UUID locationId;
    private final BigDecimal requested;
    private final BigDecimal available;

    public InsufficientStockException(UUID productId, UUID locationId, BigDecimal requested) {
        this(productId, locationId, requested, null);
    }

    private InsufficientStockException(UUID productId, UUID locationId,
                                       BigDecimal requested, BigDecimal available) {
        super("Insufficient stock for product " + productId);
        this.productId = productId;
        this.locationId = locationId;
        this.requested = requested;
        this.available = available;
    }

    public InsufficientStockException withAvailable(BigDecimal actuallyAvailable) {
        return new InsufficientStockException(productId, locationId, requested, actuallyAvailable);
    }

    public UUID productId() {
        return productId;
    }

    public UUID locationId() {
        return locationId;
    }

    public BigDecimal requested() {
        return requested;
    }

    public BigDecimal available() {
        return available;
    }
}
