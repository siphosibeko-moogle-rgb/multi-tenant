package com.example.inventory.suppliers;

import java.math.BigDecimal;
import java.util.UUID;

public final class SupplierDtos {

    private SupplierDtos() {
    }

    public record Supplier(
            UUID id,
            String name,
            String contactName,
            String email,
            String phone,
            Integer leadTimeDays,
            BigDecimal minOrderValue,
            boolean isActive) {
    }

    public record ObservedLeadTime(
            BigDecimal averageDays,
            BigDecimal stddevDays,
            int sampleSize,
            BigDecimal onTimeRate) {
    }

    public record SupplierDetail(
            UUID id,
            String name,
            String contactName,
            String email,
            String phone,
            Integer leadTimeDays,
            BigDecimal minOrderValue,
            boolean isActive,
            String address,
            String notes,
            ObservedLeadTime observedLeadTime,
            int productCount) {
    }
}
