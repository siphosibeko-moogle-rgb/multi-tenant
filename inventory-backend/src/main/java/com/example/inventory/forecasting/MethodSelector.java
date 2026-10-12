package com.example.inventory.forecasting;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

@Component
public class MethodSelector {

    static final int MIN_HISTORY_DAYS = 42;

    static final int MIN_NON_ZERO_ELIGIBLE_DAYS = 10;

    static final BigDecimal INTERMITTENT_FRACTION = new BigDecimal("0.3");

    static final BigDecimal TREND_THRESHOLD = new BigDecimal("0.5");

    static final BigDecimal SEASONALITY_THRESHOLD = new BigDecimal("0.35");

    public record Selection(
            ForecastMethod method,
            int historyDays,
            int eligibleDays,
            int nonZeroEligibleDays,
            BigDecimal nonzeroFraction,
            BigDecimal relativeTrend,
            BigDecimal seasonalityIndicator,
            int daysShortOfHistory,
            int nonZeroDaysShortOfCount) {

        public boolean isReady() {
            return method != ForecastMethod.INSUFFICIENT_DATA;
        }

        public boolean isSeasonalitySuspected() {
            return isReady() && seasonalityIndicator.compareTo(SEASONALITY_THRESHOLD) > 0;
        }

        public int daysShort() {
            return Math.max(daysShortOfHistory, nonZeroDaysShortOfCount);
        }
    }

    public Selection select(DemandSeries series) {
        int historyDays = series.historyDays();
        int eligibleDays = series.eligibleDayCount();
        int nonZeroEligible = series.nonZeroEligibleDayCount();
        BigDecimal fraction = series.nonzeroFraction();
        BigDecimal trend = series.relativeTrend();
        BigDecimal seasonality = series.seasonalityIndicator();

        int daysShortOfHistory = Math.max(0, MIN_HISTORY_DAYS - historyDays);
        int nonZeroShort = Math.max(0, MIN_NON_ZERO_ELIGIBLE_DAYS - nonZeroEligible);

        if (daysShortOfHistory > 0 || nonZeroShort > 0) {
            return new Selection(ForecastMethod.INSUFFICIENT_DATA, historyDays, eligibleDays,
                    nonZeroEligible, fraction, trend, seasonality, daysShortOfHistory, nonZeroShort);
        }

        ForecastMethod method;
        if (fraction.compareTo(INTERMITTENT_FRACTION) < 0) {
            method = ForecastMethod.CROSTON;
        } else if (trend.abs().compareTo(TREND_THRESHOLD) > 0) {
            method = ForecastMethod.WEIGHTED_MOVING_AVERAGE;
        } else {
            method = ForecastMethod.MOVING_AVERAGE;
        }
        return new Selection(method, historyDays, eligibleDays, nonZeroEligible, fraction, trend,
                seasonality, 0, 0);
    }
}
