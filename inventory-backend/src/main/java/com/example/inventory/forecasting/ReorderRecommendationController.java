package com.example.inventory.forecasting;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.inventory.forecasting.ForecastDtos.DismissRequest;
import com.example.inventory.forecasting.ForecastDtos.RecommendationPage;
import com.example.inventory.tenancy.TenantContext;
import com.example.inventory.web.NotFoundException;

@RestController
@RequestMapping("/reorder-recommendations")
public class ReorderRecommendationController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private static final String DEFAULT_STATUS = "open";

    private final ForecastQueries queries;

    public ReorderRecommendationController(ForecastQueries queries) {
        this.queries = queries;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('owner', 'manager', 'clerk', 'viewer')")
    public RecommendationPage list(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID supplierId) {

        return queries.listRecommendations(normaliseStatus(status), supplierId, cursor,
                clampLimit(limit));
    }

    private String normaliseStatus(String status) {
        if (status == null || status.isBlank()) {
            return DEFAULT_STATUS;
        }
        String candidate = status.trim().toLowerCase(java.util.Locale.ROOT);
        if (!VALID_STATUSES.contains(candidate)) {
            throw new com.example.inventory.web.BadRequestException(
                    "Unknown status '" + status + "'. Valid values are "
                            + String.join(", ", VALID_STATUSES) + ".");
        }
        return candidate;
    }

    private static final java.util.List<String> VALID_STATUSES =
            java.util.List.of("open", "ordered", "dismissed", "expired");

    @PostMapping("/{recommendationId}/dismiss")
    @PreAuthorize("hasAnyRole('owner', 'manager')")
    public ResponseEntity<Void> dismiss(
            @PathVariable UUID recommendationId,
            @RequestBody(required = false) DismissRequest request) {

        String reason = request == null ? null : request.reason();
        UUID actor = TenantContext.currentUserId().orElse(null);

        if (!queries.dismiss(recommendationId, reason, actor)) {
            throw new NotFoundException(
                    "No open reorder recommendation with id " + recommendationId);
        }
        return ResponseEntity.noContent().build();
    }

    private int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
