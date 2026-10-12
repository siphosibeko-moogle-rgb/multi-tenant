package com.example.inventory.reporting;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.inventory.reporting.ReportDtos.DashboardSummary;
import com.example.inventory.reporting.ReportDtos.InventoryValuation;
import com.example.inventory.reporting.ReportDtos.SalesSummaryResponse;
import com.example.inventory.reporting.ReportDtos.TopProduct;
import com.example.inventory.web.BadRequestException;

@RestController
public class ReportsController {

    private static final int TODAY_DAYS = 1;
    private static final int WEEK_DAYS = 7;
    private static final int MONTH_DAYS = 30;

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final ReportQueries queries;

    public ReportsController(ReportQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/reports/dashboard")
    @PreAuthorize("hasAnyRole('owner', 'manager')")
    public DashboardSummary dashboard(
            @RequestParam(required = false, defaultValue = "week") String period) {

        String normalised = normalise(period);
        int days = switch (normalised) {
            case "today" -> TODAY_DAYS;
            case "week" -> WEEK_DAYS;
            case "month" -> MONTH_DAYS;
            default -> throw new BadRequestException(
                    "Unknown period '" + period + "'. Expected one of: today, week, month.");
        };
        return queries.dashboard(normalised, days);
    }

    @GetMapping("/reports/sales-summary")
    @PreAuthorize("hasAnyRole('owner', 'manager')")
    public SalesSummaryResponse salesSummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, defaultValue = "day") String groupBy) {

        String unit = normalise(groupBy);
        if (!List.of("day", "week", "month").contains(unit)) {
            throw new BadRequestException(
                    "Unknown groupBy '" + groupBy + "'. Expected one of: day, week, month.");
        }
        if (from.isAfter(to)) {
            throw new BadRequestException(
                    "from (" + from + ") is after to (" + to + ").");
        }
        return new SalesSummaryResponse(queries.salesSummary(from, to, unit));
    }

    @GetMapping("/reports/inventory-valuation")
    @PreAuthorize("hasAnyRole('owner', 'manager')")
    public InventoryValuation inventoryValuation(
            @RequestParam(required = false) UUID locationId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {

        LocalDate today = queries.todayForTenant();
        if (asOf != null && asOf.isAfter(today)) {
            throw new BadRequestException(
                    "asOf (" + asOf + ") is in the future; the latest valuation is " + today + ".");
        }
        LocalDate effective = asOf == null ? today : asOf;

        boolean replay = effective.isBefore(today);
        return queries.inventoryValuation(locationId, effective, replay);
    }

    @GetMapping("/reports/top-products")
    @PreAuthorize("hasAnyRole('owner', 'manager')")
    public List<TopProduct> topProducts(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, defaultValue = "best") String order,
            @RequestParam(required = false) Integer limit) {

        String direction = normalise(order);
        if (!List.of("best", "worst").contains(direction)) {
            throw new BadRequestException(
                    "Unknown order '" + order + "'. Expected one of: best, worst.");
        }

        LocalDate today = queries.todayForTenant();
        LocalDate effectiveTo = to == null ? today : to;
        LocalDate effectiveFrom = from == null
                ? effectiveTo.minusDays(ReportQueries.TOP_PRODUCTS_DEFAULT_DAYS - 1L)
                : from;
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new BadRequestException(
                    "from (" + effectiveFrom + ") is after to (" + effectiveTo + ").");
        }

        return queries.topProducts(effectiveFrom, effectiveTo, direction, clampLimit(limit));
    }

    private static String normalise(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
