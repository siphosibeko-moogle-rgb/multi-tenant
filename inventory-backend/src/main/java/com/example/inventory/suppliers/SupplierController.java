package com.example.inventory.suppliers;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.inventory.suppliers.SupplierDtos.SupplierDetail;
import com.example.inventory.web.NotFoundException;

@RestController
@RequestMapping("/suppliers")
public class SupplierController {

    private final SupplierService suppliers;

    public SupplierController(SupplierService suppliers) {
        this.suppliers = suppliers;
    }

    @GetMapping("/{supplierId}")
    @PreAuthorize("hasAnyRole('owner', 'manager', 'clerk', 'viewer')")
    ResponseEntity<SupplierDetail> get(@PathVariable UUID supplierId) {
        return suppliers.read(supplierId)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new NotFoundException("No such supplier"));
    }
}
