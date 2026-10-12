package com.example.inventory.purchasing;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.inventory.purchasing.PurchaseOrderDtos.PurchaseOrderDetail;
import com.example.inventory.purchasing.PurchaseOrderDtos.PurchaseOrderPage;
import com.example.inventory.purchasing.PurchaseOrderDtos.PurchaseOrderWriteRequest;
import com.example.inventory.purchasing.PurchaseOrderDtos.ReceiptRequest;
import com.example.inventory.web.NotFoundException;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/purchase-orders")
public class PurchaseOrderController {

    private final PurchaseOrderService purchaseOrders;
    private final GoodsReceiptService receipts;

    public PurchaseOrderController(PurchaseOrderService purchaseOrders, GoodsReceiptService receipts) {
        this.purchaseOrders = purchaseOrders;
        this.receipts = receipts;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('owner', 'manager', 'clerk', 'viewer')")
    ResponseEntity<PurchaseOrderPage> list(@RequestParam(required = false) String cursor,
                                           @RequestParam(required = false) Integer limit,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) UUID supplierId) {
        return ResponseEntity.ok(purchaseOrders.list(cursor, limit, status, supplierId));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('owner', 'manager')")
    ResponseEntity<PurchaseOrderDetail> create(@Valid @RequestBody PurchaseOrderWriteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(purchaseOrders.create(request));
    }

    @GetMapping("/{poId}")
    @PreAuthorize("hasAnyRole('owner', 'manager', 'clerk', 'viewer')")
    ResponseEntity<PurchaseOrderDetail> get(@PathVariable UUID poId) {
        return purchaseOrders.read(poId)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new NotFoundException("No such purchase order"));
    }

    @PostMapping("/{poId}/submit")
    @PreAuthorize("hasAnyRole('owner', 'manager')")
    ResponseEntity<PurchaseOrderDetail> submit(@PathVariable UUID poId) {
        return ResponseEntity.ok(purchaseOrders.submit(poId));
    }

    @PostMapping("/{poId}/receipts")
    @PreAuthorize("hasAnyRole('owner', 'manager', 'clerk')")
    ResponseEntity<PurchaseOrderDetail> receive(@PathVariable UUID poId,
                                                @Valid @RequestBody ReceiptRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(receipts.receive(poId, request));
    }
}
