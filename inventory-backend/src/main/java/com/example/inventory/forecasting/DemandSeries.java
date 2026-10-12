package com.example.inventory.forecasting;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

public record DemandSeries(UUID productId, UUID locationId, List<Day> days) {

    public record Day(LocalDate day, BigDecimal unitsSold, boolean hadStockout) {
    }

    static final MathContext MC = MathContext.DECIMAL64;

    static final int STORED_SCALE = 4;

    public boolean isEmpty() {
        return days.isEmpty();
    }

    public int historyDays() {
        if (days.isEmpty()) {
            return 0;
        }
        LocalDate first = days.get(0).day();
        LocalDate last = days.get(days.size() - 1).day();
        return (int) ChronoUnit.DAYS.between(first, last) + 1;
    }

    public LocalDate firstDay() {
        return days.isEmpty() ? null : days.get(0).day();
    }

    public LocalDate lastDay() {
        return days.isEmpty() ? null : days.get(days.size() - 1).day();
    }

    public List<Day> eligible() {
        return days.stream().filter(d -> !d.hadStockout()).toList();
    }

    public int eligibleDayCount() {
        return eligible().size();
    }

    public int nonZeroEligibleDayCount() {
        return (int) eligible().stream().filter(d -> d.unitsSold().signum() > 0).count();
    }

    public BigDecimal nonzeroFraction() {
        int eligibleDays = eligibleDayCount();
        if (eligibleDays == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(nonZeroEligibleDayCount())
                .divide(BigDecimal.valueOf(eligibleDays), MC);
    }

    public BigDecimal mean() {
        List<Day> eligible = eligible();
        if (eligible.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = eligible.stream()
                .map(Day::unitsSold)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.divide(BigDecimal.valueOf(eligible.size()), MC);
    }

    public BigDecimal stddev() {
        List<Day> eligible = eligible();
        if (eligible.size() < 2) {
            return BigDecimal.ZERO;
        }
        BigDecimal mean = mean();
        BigDecimal sumOfSquares = eligible.stream()
                .map(d -> d.unitsSold().subtract(mean))
                .map(diff -> diff.multiply(diff, MC))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal variance = sumOfSquares.divide(BigDecimal.valueOf(eligible.size() - 1L), MC);
        return variance.signum() <= 0 ? BigDecimal.ZERO : variance.sqrt(MC);
    }

    public BigDecimal relativeTrend() {
        List<Day> eligible = eligible();
        int n = eligible.size();
        BigDecimal mean = mean();
        if (n < 2 || mean.signum() == 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal meanX = BigDecimal.valueOf(n - 1L).divide(BigDecimal.valueOf(2), MC);
        BigDecimal covariance = BigDecimal.ZERO;
        BigDecimal varianceX = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            BigDecimal dx = BigDecimal.valueOf(i).subtract(meanX);
            BigDecimal dy = eligible.get(i).unitsSold().subtract(mean);
            covariance = covariance.add(dx.multiply(dy, MC), MC);
            varianceX = varianceX.add(dx.multiply(dx, MC), MC);
        }
        if (varianceX.signum() == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal slope = covariance.divide(varianceX, MC);
        return slope.multiply(BigDecimal.valueOf(n - 1L), MC).divide(mean, MC);
    }

    private static final int[] CANDIDATE_LAGS = {7, 14, 30, 91, 182, 365};

    private static final BigDecimal NEGLIGIBLE_RESIDUAL_SHARE = new BigDecimal("0.01");

    public BigDecimal seasonalityIndicator() {
        List<Day> eligible = eligible();
        int n = eligible.size();
        if (n < 2 * CANDIDATE_LAGS[0]) {
            return BigDecimal.ZERO;
        }

        BigDecimal mean = mean();
        BigDecimal slope = rawSlope(eligible, mean);
        BigDecimal meanX = BigDecimal.valueOf(n - 1L).divide(BigDecimal.valueOf(2), MC);
        BigDecimal[] residuals = new BigDecimal[n];
        for (int i = 0; i < n; i++) {
            BigDecimal fitted = mean.add(
                    slope.multiply(BigDecimal.valueOf(i).subtract(meanX), MC), MC);
            residuals[i] = eligible.get(i).unitsSold().subtract(fitted);
        }

        BigDecimal denominator = BigDecimal.ZERO;
        for (BigDecimal residual : residuals) {
            denominator = denominator.add(residual.multiply(residual, MC), MC);
        }
        if (denominator.signum() == 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal residualRms = denominator.divide(BigDecimal.valueOf(n), MC).sqrt(MC);
        if (residualRms.compareTo(mean.multiply(NEGLIGIBLE_RESIDUAL_SHARE, MC)) < 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal strongest = BigDecimal.ZERO;
        for (int lag : CANDIDATE_LAGS) {
            if (lag * 2 > n) {
                continue;
            }
            BigDecimal numerator = BigDecimal.ZERO;
            for (int i = 0; i + lag < n; i++) {
                numerator = numerator.add(residuals[i].multiply(residuals[i + lag], MC), MC);
            }
            BigDecimal correlation = numerator.divide(denominator, MC).abs();
            if (correlation.compareTo(strongest) > 0) {
                strongest = correlation;
            }
        }
        return strongest;
    }

    private BigDecimal rawSlope(List<Day> eligible, BigDecimal mean) {
        int n = eligible.size();
        BigDecimal meanX = BigDecimal.valueOf(n - 1L).divide(BigDecimal.valueOf(2), MC);
        BigDecimal covariance = BigDecimal.ZERO;
        BigDecimal varianceX = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            BigDecimal dx = BigDecimal.valueOf(i).subtract(meanX);
            BigDecimal dy = eligible.get(i).unitsSold().subtract(mean);
            covariance = covariance.add(dx.multiply(dy, MC), MC);
            varianceX = varianceX.add(dx.multiply(dx, MC), MC);
        }
        return varianceX.signum() == 0 ? BigDecimal.ZERO : covariance.divide(varianceX, MC);
    }

    static BigDecimal stored(BigDecimal value) {
        return value.setScale(STORED_SCALE, RoundingMode.HALF_UP);
    }
}
