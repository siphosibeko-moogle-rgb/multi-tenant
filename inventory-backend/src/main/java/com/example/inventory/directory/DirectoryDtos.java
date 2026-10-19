package com.example.inventory.directory;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class DirectoryDtos {

    private DirectoryDtos() {
    }

    public enum BusinessType {
        WHOLESALER, MANUFACTURER, DISTRIBUTOR, RETAILER, OTHER
    }

    /** Public view of a listing. Deliberately has NO phone or email fields. */
    public record SupplierListing(
            UUID id,
            String businessName,
            BusinessType businessType,
            String contactName,
            String description,
            String category) {
    }

    public record SupplierListingPage(List<SupplierListing> items, String nextCursor) {
    }

    /** Only ever returned to the owning business. */
    public record SupplierListingOwn(
            UUID id,
            String businessName,
            BusinessType businessType,
            String contactName,
            String description,
            String category,
            String phone,
            String email,
            boolean isPublished) {
    }

    /** Only ever returned by the request-contact endpoint. */
    public record SupplierContact(String phone, String email) {
    }

    public record SupplierListingWrite(
            @NotBlank @Size(max = 150) String businessName,
            @NotNull BusinessType businessType,
            @NotBlank @Size(max = 100) String contactName,
            @NotBlank @Size(max = 30)
            @Pattern(regexp = "^\\+?[0-9 ()-]{7,20}$", message = "must be a valid phone number") String phone,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 500) String description,
            @NotBlank @Size(max = 60) String category,
            @AssertTrue(message = "consent is required to publish contact details") boolean consentGiven) {
    }
}
