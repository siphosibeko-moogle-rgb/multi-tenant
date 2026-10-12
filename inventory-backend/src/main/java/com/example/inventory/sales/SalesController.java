package com.example.inventory.sales;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.inventory.sales.SalesDtos.SaleDetail;
import com.example.inventory.sales.SalesDtos.SalePage;
import com.example.inventory.sales.SalesDtos.VoidRequest;
import com.example.inventory.sales.SalesDtos.ReturnRequest;
import com.example.inventory.sales.SalesDtos.SaleWriteRequest;
import com.example.inventory.web.NotFoundException;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/sales")
public class SalesController {

    private final SaleService sales;

    public SalesController(SaleService sales) {
        this.sales = sales;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('owner', 'manager', 'clerk', 'viewer')")
    ResponseEntity<SalePage> list(@RequestParam(required = false) String cursor,
                                  @RequestParam(required = false) Integer limit,
                                  @RequestParam(required = false) LocalDate from,
                                  @RequestParam(required = false) LocalDate to,
                                  @RequestParam(required = false) String status) {
        return ResponseEntity.ok(sales.list(cursor, limit, from, to, status));
    }

    @GetMapping("/{saleId}")
    @PreAuthorize("hasAnyRole('owner', 'manager', 'clerk', 'viewer')")
    ResponseEntity<SaleDetail> get(@PathVariable UUID saleId) {
        return sales.read(saleId)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new NotFoundException("No such sale"));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('owner', 'manager', 'clerk')")
    ResponseEntity<SaleDetail> record(
            @Valid @RequestBody SaleWriteRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) UUID idempotencyKey) {
        var recorded = sales.record(request, idempotencyKey);
        return ResponseEntity
                .status(recorded.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(recorded.sale());
    }

    @PostMapping("/{saleId}/void")
    @PreAuthorize("hasAnyRole('owner', 'manager')")
    ResponseEntity<SaleDetail> voidSale(@PathVariable UUID saleId,
                                        @RequestBody(required = false) VoidRequest request) {
        String reason = request == null ? null : request.reason();
        return ResponseEntity.ok(sales.voidSale(saleId, reason));
    }

    @PostMapping("/{saleId}/returns")
    @PreAuthorize("hasAnyRole('owner', 'manager')")
    ResponseEntity<SaleDetail> returnSale(@PathVariable UUID saleId,
                                          @Valid @RequestBody ReturnRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(sales.returnSale(saleId, request));
    }
}
