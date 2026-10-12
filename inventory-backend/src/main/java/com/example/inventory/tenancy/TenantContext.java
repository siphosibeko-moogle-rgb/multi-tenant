package com.example.inventory.tenancy;

import java.util.Optional;
import java.util.UUID;

public final class TenantContext {

    private static final ThreadLocal<TenantIdentity> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public record TenantIdentity(UUID tenantId, UUID userId, String role) {
    }

    public static void bind(TenantIdentity identity) {
        CURRENT.set(identity);
    }

    public static void bindForRegistration(UUID newTenantId) {
        CURRENT.set(new TenantIdentity(newTenantId, null, null));
    }

    public static Optional<TenantIdentity> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static Optional<UUID> currentTenantId() {
        return current().map(TenantIdentity::tenantId);
    }

    public static Optional<UUID> currentUserId() {
        return current().map(TenantIdentity::userId);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
