package com.example.inventory.forecasting;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.inventory.forecasting.ForecastDtos.ForecastDetailResponse;
import com.example.inventory.forecasting.ForecastDtos.ForecastPage;
import com.example.inventory.forecasting.ForecastDtos.ForecastResponse;
import com.example.inventory.forecasting.ForecastDtos.HistoryPoint;
import com.example.inventory.forecasting.ForecastDtos.RecomputeAccepted;
import com.example.inventory.forecasting.ForecastDtos.RecomputeRequest;
import com.example.inventory.web.NotFoundException;

@RestController
public class ForecastController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final ForecastQueries queries;
    private final ReorderService reorderService;

    public ForecastController(ForecastQueries queries, ReorderService reorderService) {
        this.queries = queries;
        this.reorderService = reorderService;
    }

    @GetMapping("/forecasts")
    @PreAuthorize("hasAnyRole('owner', 'manager', 'clerk', 'viewer')")
    public ForecastPage list(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(required = false) Integer horizonDays) {
        return queries.listForecasts(locationId, horizonDays, cursor, clampLimit(limit));
    }

    @GetMapping("/products/{productId}/forecast")
    @PreAuthorize("hasAnyRole('owner', 'manager', 'clerk', 'viewer')")
    public ForecastDetailResponse forProduct(
            @PathVariable UUID productId,
            @RequestParam(required = false, defaultValue = "false") boolean includeHistory,
            @RequestParam(required = false) UUID locationId) {

        if (!queries.productExists(productId)) {
            throw new NotFoundException("No product with id " + productId);
        }

        UUID effectiveLocation = locationId != null
                ? locationId
                : queries.defaultLocationId().orElse(null);

        ReorderService.ExplainedForecast explained =
                reorderService.explainedForecast(productId, effectiveLocation);
        Forecaster.Forecast forecast = explained.forecast();

        ForecastResponse stored = queries.currentForecast(productId, effectiveLocation)
                .orElse(null);
        List<HistoryPoint> history = includeHistory
                ? queries.history(productId, effectiveLocation)
                : List.of();

        return new ForecastDetailResponse(
                productId,
                forecast.locationId(),
                forecast.method().dbValue(),
                stored == null ? java.time.OffsetDateTime.now() : stored.generatedAt(),
                forecast.selection().historyDays(),
                forecast.avgDailyDemand(),
                forecast.demandStddev(),
                forecast.horizonDays(),
                forecast.forecastQty(),
                forecast.daysOfCover(),
                forecast.projectedStockoutOn(),
                forecast.reorderPoint(),
                forecast.serviceLevel(),
                forecast.confidence(),
                explained.explanation(),
                history);
    }

    @PostMapping("/forecasts/recompute")
    @PreAuthorize("hasAnyRole('owner', 'manager')")
    public ResponseEntity<RecomputeAccepted> recompute(
            @RequestBody(required = false) RecomputeRequest request) {

        ReorderService.RecomputeResult result = reorderService.recomputeAll();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new RecomputeAccepted(UUID.randomUUID(), result.forecastsWritten()));
    }

    private int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
