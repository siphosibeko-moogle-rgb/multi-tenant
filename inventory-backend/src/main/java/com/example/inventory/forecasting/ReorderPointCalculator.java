package com.example.inventory.forecasting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.NavigableMap;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

@Component
public class ReorderPointCalculator {

    private static final NavigableMap<BigDecimal, BigDecimal> Z_BY_SERVICE_LEVEL = new TreeMap<>();

    static {
        Z_BY_SERVICE_LEVEL.put(new BigDecimal("0.500"), new BigDecimal("0.000"));
        Z_BY_SERVICE_LEVEL.put(new BigDecimal("0.750"), new BigDecimal("0.674"));
        Z_BY_SERVICE_LEVEL.put(new BigDecimal("0.800"), new BigDecimal("0.842"));
        Z_BY_SERVICE_LEVEL.put(new BigDecimal("0.850"), new BigDecimal("1.036"));
        Z_BY_SERVICE_LEVEL.put(new BigDecimal("0.900"), new BigDecimal("1.282"));
        Z_BY_SERVICE_LEVEL.put(new BigDecimal("0.950"), new BigDecimal("1.645"));
        Z_BY_SERVICE_LEVEL.put(new BigDecimal("0.975"), new BigDecimal("1.960"));
        Z_BY_SERVICE_LEVEL.put(new BigDecimal("0.990"), new BigDecimal("2.326"));
        Z_BY_SERVICE_LEVEL.put(new BigDecimal("0.999"), new BigDecimal("3.090"));
    }

    public static final BigDecimal DEFAULT_SERVICE_LEVEL = new BigDecimal("0.950");

    public record ReorderPoint(BigDecimal reorderPoint, BigDecimal safetyStock,
                               BigDecimal leadTimeDemand, BigDecimal z) {
    }

    public ReorderPoint calculate(BigDecimal avgDailyDemand, BigDecimal demandStddev,
                                  BigDecimal leadTimeDays, BigDecimal serviceLevel) {
        if (avgDailyDemand.signum() < 0 || demandStddev.signum() < 0
                || leadTimeDays.signum() < 0) {
            throw new IllegalArgumentException(
                    "demand, spread and lead time must all be non-negative; got "
                            + avgDailyDemand + ", " + demandStddev + ", " + leadTimeDays);
        }

        BigDecimal z = zFor(serviceLevel);
        BigDecimal leadTimeDemand = avgDailyDemand.multiply(leadTimeDays, DemandSeries.MC);

        BigDecimal sqrtLeadTime = leadTimeDays.signum() == 0
                ? BigDecimal.ZERO
                : leadTimeDays.sqrt(DemandSeries.MC);
        BigDecimal safetyStock = z.multiply(demandStddev, DemandSeries.MC)
                .multiply(sqrtLeadTime, DemandSeries.MC);

        return new ReorderPoint(
                round(leadTimeDemand.add(safetyStock, DemandSeries.MC)),
                round(safetyStock),
                round(leadTimeDemand),
                z);
    }

    BigDecimal zFor(BigDecimal serviceLevel) {
        BigDecimal below = Z_BY_SERVICE_LEVEL.floorKey(serviceLevel);
        BigDecimal above = Z_BY_SERVICE_LEVEL.ceilingKey(serviceLevel);

        if (below == null) {
            return Z_BY_SERVICE_LEVEL.firstEntry().getValue();
        }
        if (above == null) {
            return Z_BY_SERVICE_LEVEL.lastEntry().getValue();
        }
        BigDecimal distanceBelow = serviceLevel.subtract(below);
        BigDecimal distanceAbove = above.subtract(serviceLevel);
        return Z_BY_SERVICE_LEVEL.get(distanceAbove.compareTo(distanceBelow) <= 0 ? above : below);
    }

    private static BigDecimal round(BigDecimal value) {
        return value.setScale(3, RoundingMode.HALF_UP);
    }
}
