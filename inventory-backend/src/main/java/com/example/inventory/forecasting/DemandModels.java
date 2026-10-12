package com.example.inventory.forecasting;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Component;

import com.example.inventory.forecasting.DemandSeries.Day;

@Component
public class DemandModels {

    static final BigDecimal CROSTON_ALPHA = new BigDecimal("0.1");

    public BigDecimal averageDailyDemand(ForecastMethod method, DemandSeries series) {
        return switch (method) {
            case MOVING_AVERAGE -> movingAverage(series);
            case WEIGHTED_MOVING_AVERAGE -> weightedMovingAverage(series);
            case CROSTON -> croston(series);
            case EXPONENTIAL_SMOOTHING, ML_MODEL, NAIVE -> throw new IllegalArgumentException(
                    method.dbValue() + " is declared but not implemented — MethodSelector "
                            + "never returns it. See MethodSelector's Javadoc.");
            case INSUFFICIENT_DATA -> throw new IllegalArgumentException(
                    "insufficient_data has no demand estimate by definition. The caller must "
                            + "branch on Selection.isReady() rather than asking for a number "
                            + "and getting a zero it cannot tell from a real one.");
        };
    }

    private BigDecimal movingAverage(DemandSeries series) {
        return series.mean();
    }

    private BigDecimal weightedMovingAverage(DemandSeries series) {
        List<Day> eligible = series.eligible();
        if (eligible.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal weightedTotal = BigDecimal.ZERO;
        BigDecimal weightSum = BigDecimal.ZERO;
        for (int i = 0; i < eligible.size(); i++) {
            BigDecimal weight = BigDecimal.valueOf(i + 1L);
            weightedTotal = weightedTotal.add(
                    eligible.get(i).unitsSold().multiply(weight, DemandSeries.MC), DemandSeries.MC);
            weightSum = weightSum.add(weight);
        }
        return weightedTotal.divide(weightSum, DemandSeries.MC);
    }

    private BigDecimal croston(DemandSeries series) {
        List<Day> eligible = series.eligible();

        BigDecimal size = null;
        BigDecimal interval = null;
        int gap = 0;

        for (Day day : eligible) {
            gap++;
            if (day.unitsSold().signum() <= 0) {
                continue;
            }
            if (size == null) {
                size = day.unitsSold();
            } else if (interval == null) {
                interval = BigDecimal.valueOf(gap);
                size = smooth(size, day.unitsSold());
            } else {
                size = smooth(size, day.unitsSold());
                interval = smooth(interval, BigDecimal.valueOf(gap));
            }
            gap = 0;
        }

        if (size == null || interval == null || interval.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return size.divide(interval, DemandSeries.MC);
    }

    private BigDecimal smooth(BigDecimal previous, BigDecimal observation) {
        return CROSTON_ALPHA.multiply(observation, DemandSeries.MC)
                .add(BigDecimal.ONE.subtract(CROSTON_ALPHA)
                        .multiply(previous, DemandSeries.MC), DemandSeries.MC);
    }
}
