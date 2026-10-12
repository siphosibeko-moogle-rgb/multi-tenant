package com.example.inventory.forecasting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.example.inventory.forecasting.LeadTimeResolver.LeadTime;
import com.example.inventory.forecasting.MethodSelector.Selection;
import com.example.inventory.forecasting.ReorderPointCalculator.ReorderPoint;

@Service
public class Forecaster {

    private final DemandSeriesRepository seriesRepository;
    private final MethodSelector selector;
    private final DemandModels models;
    private final LeadTimeResolver leadTimes;
    private final ReorderPointCalculator reorderPoints;
    private final ForecastingProperties properties;

    public Forecaster(DemandSeriesRepository seriesRepository,
                      MethodSelector selector,
                      DemandModels models,
                      LeadTimeResolver leadTimes,
                      ReorderPointCalculator reorderPoints,
                      ForecastingProperties properties) {
        this.seriesRepository = seriesRepository;
        this.selector = selector;
        this.models = models;
        this.leadTimes = leadTimes;
        this.reorderPoints = reorderPoints;
        this.properties = properties;
    }

    public record Forecast(
            UUID productId,
            UUID locationId,
            Selection selection,
            BigDecimal avgDailyDemand,
            BigDecimal demandStddev,
            int horizonDays,
            BigDecimal forecastQty,
            BigDecimal quantityOnHand,
            BigDecimal daysOfCover,
            LocalDate projectedStockoutOn,
            BigDecimal reorderPoint,
            BigDecimal safetyStock,
            LeadTime leadTime,
            BigDecimal serviceLevel,
            BigDecimal confidence,
            LocalDate historyFrom,
            LocalDate historyTo,
            boolean seasonalitySuspected) {

        public ForecastMethod method() {
            return selection.method();
        }

        public boolean hasReorderPoint() {
            return reorderPoint != null;
        }
    }

    private BigDecimal confidenceFor(Selection selection, BigDecimal avgDaily,
                                     BigDecimal stddev) {
        BigDecimal evidence = BigDecimal.valueOf(
                        Math.min(selection.nonZeroEligibleDays(), 30))
                .divide(BigDecimal.valueOf(30), DemandSeries.MC);

        BigDecimal steadiness = BigDecimal.ONE;
        if (avgDaily.signum() > 0) {
            BigDecimal coefficientOfVariation = stddev.divide(avgDaily, DemandSeries.MC);
            steadiness = BigDecimal.ONE.divide(
                    BigDecimal.ONE.add(coefficientOfVariation), DemandSeries.MC);
        }

        BigDecimal seasonal = selection.isSeasonalitySuspected()
                ? new BigDecimal("0.6") : BigDecimal.ONE;

        return evidence.multiply(steadiness, DemandSeries.MC)
                .multiply(seasonal, DemandSeries.MC)
                .setScale(3, RoundingMode.HALF_UP);
    }

    public Forecast forecast(UUID productId, UUID locationId) {
        DemandSeries series = seriesRepository.load(productId, locationId);
        return forecast(series, LocalDate.now());
    }

    Forecast forecast(DemandSeries series, LocalDate today) {
        Selection selection = selector.select(series);
        BigDecimal onHand = seriesRepository.quantityOnHand(
                series.productId(), series.locationId());
        BigDecimal serviceLevel = ReorderPointCalculator.DEFAULT_SERVICE_LEVEL;
        int horizon = properties.horizonDays();

        if (!selection.isReady()) {
            return new Forecast(series.productId(), series.locationId(), selection,
                    BigDecimal.ZERO, BigDecimal.ZERO, horizon, BigDecimal.ZERO,
                    onHand, null, null, null, null, null, serviceLevel, null,
                    series.firstDay(), series.lastDay(), false);
        }

        BigDecimal avgDaily = DemandSeries.stored(
                models.averageDailyDemand(selection.method(), series));
        BigDecimal stddev = DemandSeries.stored(series.stddev());
        BigDecimal forecastQty = avgDaily.multiply(BigDecimal.valueOf(horizon), DemandSeries.MC)
                .setScale(3, RoundingMode.HALF_UP);

        BigDecimal daysOfCover = null;
        LocalDate projectedStockout = null;
        if (avgDaily.signum() > 0) {
            daysOfCover = onHand.divide(avgDaily, DemandSeries.MC).setScale(2, RoundingMode.HALF_UP);
            projectedStockout = today.plusDays(daysOfCover.setScale(0, RoundingMode.FLOOR)
                    .longValueExact());
        }

        Optional<LeadTime> leadTime = leadTimes.forProduct(series.productId());
        BigDecimal reorderPoint = null;
        BigDecimal safetyStock = null;
        if (leadTime.isPresent() && avgDaily.signum() > 0) {
            ReorderPoint computed = reorderPoints.calculate(
                    avgDaily, stddev, leadTime.get().days(), serviceLevel);
            reorderPoint = computed.reorderPoint();
            safetyStock = computed.safetyStock();
        }

        return new Forecast(series.productId(), series.locationId(), selection,
                avgDaily, stddev, horizon, forecastQty, onHand, daysOfCover,
                projectedStockout, reorderPoint, safetyStock, leadTime.orElse(null),
                serviceLevel, confidenceFor(selection, avgDaily, stddev),
                series.firstDay(), series.lastDay(), selection.isSeasonalitySuspected());
    }
}
