package com.example.inventory.catalog;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.inventory.catalog.CatalogDtos.Category;
import com.example.inventory.catalog.CatalogDtos.CategoryWriteRequest;
import com.example.inventory.catalog.CatalogDtos.Location;
import com.example.inventory.catalog.CatalogDtos.LocationWriteRequest;
import com.example.inventory.catalog.CatalogDtos.Product;
import com.example.inventory.catalog.CatalogDtos.ProductPage;
import com.example.inventory.catalog.CatalogDtos.ProductWriteRequest;
import com.example.inventory.web.NotFoundException;

import jakarta.validation.Valid;

@RestController
public class CatalogController {

    private final ProductCatalog catalog;

    public CatalogController(ProductCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/products")
    @PreAuthorize("hasAnyRole('owner', 'manager', 'clerk', 'viewer')")
    ResponseEntity<ProductPage> list(@RequestParam(required = false) String cursor,
                                     @RequestParam(required = false) Integer limit,
                                     @RequestParam(required = false) String q,
                                     @RequestParam(required = false) UUID categoryId,
                                     @RequestParam(required = false) Boolean active) {
        return ResponseEntity.ok(catalog.list(cursor, limit, q, categoryId, active));
    }

    @GetMapping("/products/lookup")
    @PreAuthorize("hasAnyRole('owner', 'manager', 'clerk', 'viewer')")
    ResponseEntity<Product> lookup(@RequestParam String barcode) {
        return catalog.findByBarcode(barcode)
                .map(CatalogController::withETag)
                .orElseThrow(() -> new NotFoundException("No product with that barcode"));
    }

    @GetMapping("/products/{productId}")
    @PreAuthorize("hasAnyRole('owner', 'manager', 'clerk', 'viewer')")
    ResponseEntity<Product> get(@PathVariable UUID productId) {
        return catalog.find(productId)
                .map(CatalogController::withETag)
                .orElseThrow(() -> new NotFoundException("No such product"));
    }

    @PostMapping("/products")
    @PreAuthorize("hasAnyRole('owner', 'manager')")
    ResponseEntity<Product> create(@Valid @RequestBody ProductWriteRequest request) {
        Versioned<Product> created = catalog.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(created.etag())
                .body(created.value());
    }

    @PatchMapping("/products/{productId}")
    @PreAuthorize("hasAnyRole('owner', 'manager')")
    ResponseEntity<Product> update(@PathVariable UUID productId,
                                   @Valid @RequestBody ProductWriteRequest request,
                                   @RequestHeader(value = HttpHeaders.IF_MATCH, required = false)
                                   String ifMatch) {
        return withETag(catalog.update(productId, request, ifMatch));
    }

    @DeleteMapping("/products/{productId}")
    @PreAuthorize("hasAnyRole('owner', 'manager')")
    ResponseEntity<Void> deactivate(@PathVariable UUID productId) {
        if (!catalog.deactivate(productId)) {
            throw new NotFoundException("No such product");
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/categories")
    @PreAuthorize("hasAnyRole('owner', 'manager', 'clerk', 'viewer')")
    ResponseEntity<List<Category>> categories() {
        return ResponseEntity.ok(catalog.categories());
    }

    @PostMapping("/categories")
    @PreAuthorize("hasAnyRole('owner', 'manager')")
    ResponseEntity<Category> createCategory(@Valid @RequestBody CategoryWriteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalog.createCategory(request));
    }

    @GetMapping("/locations")
    @PreAuthorize("hasAnyRole('owner', 'manager', 'clerk', 'viewer')")
    ResponseEntity<List<Location>> locations() {
        return ResponseEntity.ok(catalog.locations());
    }

    @PostMapping("/locations")
    @PreAuthorize("hasAnyRole('owner', 'manager')")
    ResponseEntity<Location> createLocation(@Valid @RequestBody LocationWriteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalog.createLocation(request));
    }

    private static ResponseEntity<Product> withETag(Versioned<Product> product) {
        return ResponseEntity.ok().eTag(product.etag()).body(product.value());
    }
}
