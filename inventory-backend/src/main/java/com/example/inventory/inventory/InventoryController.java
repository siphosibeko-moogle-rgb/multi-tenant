package com.example.inventory.inventory;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.inventory.inventory.InventoryDtos.AdjustmentRequest;
import com.example.inventory.inventory.InventoryDtos.MovementPage;
import com.example.inventory.inventory.InventoryDtos.StockMovement;
import com.example.inventory.inventory.InventoryDtos.StockStatusPage;
import com.example.inventory.inventory.InventoryDtos.TransferRequest;
import com.example.inventory.inventory.StockLedgerService.MovementRequest;
import com.example.inventory.web.ConflictException;
import com.example.inventory.web.NotFoundException;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/inventory")
public class InventoryController {

    private final StockLedgerService ledger;
    private final InventoryQueries queries;

    public InventoryController(StockLedgerService ledger, InventoryQueries queries) {
        this.ledger = ledger;
        this.queries = queries;
    }

    @PostMapping("/adjustments")
    @PreAuthorize("hasAnyRole('owner', 'manager')")
    ResponseEntity<StockMovement> adjust(
            @Valid @RequestBody AdjustmentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) UUID idempotencyKey) {
        UUID locationId = request.locationId() != null
                ? request.locationId()
                : queries.defaultLocationId().orElseThrow(() -> new ConflictException(
                        "This business has no default location; specify locationId",
                        "no-default-location"));

        var posted = ledger.post(new MovementRequest(
                request.productId(),
                locationId,
                "adjustment",
                request.quantityDelta(),
                request.unitCost(),
                null,
                null,
                request.reason(),
                request.occurredAt()), idempotencyKey);

        return ResponseEntity
                .status(posted.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(toDto(posted.movement()));
    }

    @PostMapping("/transfers")
    @PreAuthorize("hasAnyRole('owner', 'manager', 'clerk')")
    ResponseEntity<List<StockMovement>> transfer(@Valid @RequestBody TransferRequest request) {
        var transferred = ledger.transfer(new StockLedgerService.TransferRequest(
                request.productId(),
                request.fromLocationId(),
                request.toLocationId(),
                request.quantity(),
                request.reason(),
                UUID.randomUUID(),
                request.occurredAt()));

        return ResponseEntity.status(HttpStatus.CREATED).body(List.of(
                toDto(transferred.out()), toDto(transferred.in())));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('owner', 'manager', 'clerk', 'viewer')")
    ResponseEntity<StockStatusPage> stock(@RequestParam(required = false) String cursor,
                                          @RequestParam(required = false) Integer limit,
                                          @RequestParam(required = false) UUID locationId,
                                          @RequestParam(required = false) String stockState) {
        return ResponseEntity.ok(queries.stock(cursor, limit, locationId, stockState));
    }

    private static StockMovement toDto(StockLedgerService.PostedMovement posted) {
        return new StockMovement(
                posted.id(),
                posted.productId(),
                posted.productName(),
                posted.locationId(),
                posted.movementType(),
                posted.quantityDelta(),
                posted.balanceAfter(),
                posted.unitCost(),
                null,
                null,
                posted.reason(),
                posted.occurredAt(),
                posted.createdBy(),
                posted.createdByName());
    }

    @GetMapping("/movements")
    @PreAuthorize("hasAnyRole('owner', 'manager', 'clerk', 'viewer')")
    ResponseEntity<MovementPage> movements(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return ResponseEntity.ok(
                queries.movements(cursor, limit, productId, locationId, type, from, to));
    }
}
