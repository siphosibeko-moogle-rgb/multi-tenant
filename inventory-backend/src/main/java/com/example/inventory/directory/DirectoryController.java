package com.example.inventory.directory;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.inventory.directory.DirectoryDtos.BusinessType;
import com.example.inventory.directory.DirectoryDtos.SupplierContact;
import com.example.inventory.directory.DirectoryDtos.SupplierListing;
import com.example.inventory.directory.DirectoryDtos.SupplierListingOwn;
import com.example.inventory.directory.DirectoryDtos.SupplierListingPage;
import com.example.inventory.directory.DirectoryDtos.SupplierListingWrite;
import com.example.inventory.web.NotFoundException;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/supplier-directory")
public class DirectoryController {

    private static final String ANY_ROLE = "hasAnyRole('owner', 'manager', 'clerk', 'viewer')";
    private static final String OWNER_OR_MANAGER = "hasAnyRole('owner', 'manager')";

    private final DirectoryService directory;

    public DirectoryController(DirectoryService directory) {
        this.directory = directory;
    }

    @GetMapping
    @PreAuthorize(ANY_ROLE)
    SupplierListingPage search(@RequestParam(required = false) String q,
                               @RequestParam(required = false) String category,
                               @RequestParam(required = false) BusinessType businessType,
                               @RequestParam(required = false) String cursor,
                               @RequestParam(required = false) Integer limit) {
        return directory.search(q, category, businessType, cursor, limit);
    }

    // "/me" is a literal path, so it wins over "/{id}"
    @GetMapping("/me")
    @PreAuthorize(OWNER_OR_MANAGER)
    ResponseEntity<SupplierListingOwn> mine() {
        return directory.readMine()
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new NotFoundException("Your business has no listing"));
    }

    @PutMapping("/me")
    @PreAuthorize(OWNER_OR_MANAGER)
    SupplierListingOwn upsertMine(@Valid @RequestBody SupplierListingWrite body) {
        return directory.upsertMine(body);
    }

    @DeleteMapping("/me")
    @PreAuthorize(OWNER_OR_MANAGER)
    ResponseEntity<Void> withdrawMine() {
        directory.withdrawMine();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    @PreAuthorize(ANY_ROLE)
    ResponseEntity<SupplierListing> get(@PathVariable UUID id) {
        return directory.read(id)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new NotFoundException("No such listing"));
    }

    @PostMapping("/{id}/contact-requests")
    @PreAuthorize(OWNER_OR_MANAGER)
    SupplierContact requestContact(@PathVariable UUID id) {
        return directory.requestContact(id);
    }
}
