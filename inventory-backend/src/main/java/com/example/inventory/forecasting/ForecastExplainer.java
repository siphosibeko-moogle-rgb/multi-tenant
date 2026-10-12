package com.example.inventory.forecasting;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

import com.example.inventory.forecasting.Forecaster.Forecast;
import com.example.inventory.forecasting.LeadTimeResolver.LeadTime;

@Component
public class ForecastExplainer {

    private static final int DAYS_PER_WEEK = 7;

    static final BigDecimal LOW_CONFIDENCE_THRESHOLD = new BigDecimal("0.40");

    private static final int THIN_EVIDENCE_DAYS = 30;

    private static final BigDecimal ERRATIC_VARIATION = BigDecimal.ONE;

    public String explain(Forecast forecast, String productName) {
        if (!forecast.selection().isReady()) {
            return stillLearning(forecast, productName);
        }

        StringBuilder text = new StringBuilder();
        BigDecimal weekly = forecast.avgDailyDemand()
                .multiply(BigDecimal.valueOf(DAYS_PER_WEEK), DemandSeries.MC);
        text.append("You sell about ").append(friendly(weekly)).append(" a week. ");

        text.append(friendly(forecast.quantityOnHand())).append(" in stock ");
        if (forecast.daysOfCover() == null) {
            text.append("and no recent sales, so that will last indefinitely on current demand.");
        } else {
            text.append("covers roughly ")
                    .append(forecast.daysOfCover().setScale(0, RoundingMode.HALF_UP))
                    .append(" days");
            if (forecast.leadTime() != null) {
                text.append(", and your supplier ").append(leadTimePhrase(forecast.leadTime()));
            }
            text.append('.');
        }

        if (forecast.hasReorderPoint()) {
            text.append(" Reorder when you get down to about ")
                    .append(friendly(forecast.reorderPoint())).append('.');
        } else if (forecast.leadTime() == null) {
            text.append(" No supplier is recorded for this product, so there is no delivery "
                    + "time to plan around — add one to get a reorder level.");
        }

        appendCaveats(text, forecast);
        return text.toString();
    }

    public String explainRecommendation(Forecast forecast, BigDecimal recommendedQty,
                                        Urgency urgency, String supplierName) {
        StringBuilder text = new StringBuilder();

        text.append(switch (urgency) {
            case CRITICAL -> forecast.quantityOnHand().signum() <= 0
                    ? "You are out of stock now. "
                    : "This will run out before a new order can arrive. ";
            case HIGH -> "This is running low and needs ordering soon. ";
            case NORMAL -> "Stock has dropped to the reorder level. ";
        });

        text.append(friendly(forecast.quantityOnHand())).append(" left");
        if (forecast.daysOfCover() != null) {
            text.append(", about ").append(forecast.daysOfCover().setScale(0, RoundingMode.HALF_UP))
                    .append(" days at your usual rate");
        }
        text.append(". ");

        if (forecast.leadTime() != null) {
            String supplier = supplierName == null ? "Your supplier" : supplierName;
            text.append(supplier).append(' ').append(leadTimePhrase(forecast.leadTime()))
                    .append(". ");
        }

        text.append("Order about ").append(friendly(recommendedQty))
                .append(" to cover the wait plus roughly the next ")
                .append(forecast.horizonDays()).append(" days.");

        appendCaveats(text, forecast);
        return text.toString();
    }

    private String stillLearning(Forecast forecast, String productName) {
        MethodSelector.Selection selection = forecast.selection();
        StringBuilder text = new StringBuilder("Still learning ")
                .append(productName == null ? "this product" : productName).append(" — ");

        if (selection.historyDays() == 0) {
            return text.append("no sales history recorded yet. Once it has been on sale for "
                    + "about six weeks there will be enough to work from.").toString();
        }

        text.append(selection.historyDays())
                .append(selection.historyDays() == 1 ? " day" : " days")
                .append(" of history so far");

        if (selection.daysShortOfHistory() > 0) {
            text.append(", about ").append(selection.daysShortOfHistory())
                    .append(" more needed");
        }
        if (selection.nonZeroDaysShortOfCount() > 0) {
            text.append(selection.daysShortOfHistory() > 0 ? ", and " : ", but ");
            if (selection.nonZeroEligibleDays() == 0) {
                text.append("it has not sold at all yet — about ")
                        .append(selection.nonZeroDaysShortOfCount())
                        .append(" days with a sale are needed before there is anything to "
                                + "forecast from");
            } else {
                text.append("it has only sold on ").append(selection.nonZeroEligibleDays())
                        .append(selection.nonZeroEligibleDays() == 1 ? " day" : " days")
                        .append(" — about ").append(selection.nonZeroDaysShortOfCount())
                        .append(" more days with a sale are needed");
            }
        }

        return text.append(". Until then a reorder level would be a guess rather than a "
                + "forecast.").toString();
    }

    private String leadTimePhrase(LeadTime leadTime) {
        String days = friendly(leadTime.days()) + (isOne(leadTime.days()) ? " day" : " days");
        return switch (leadTime.source()) {
            case OBSERVED -> "has been taking about " + days + " to deliver (measured over "
                    + leadTime.sampleSize() + " deliveries)";
            case PROMISED_FOR_PRODUCT, PROMISED_BY_SUPPLIER ->
                    "quotes " + days + " to deliver (their stated time — you have not had "
                            + "enough deliveries yet to measure the real one)";
        };
    }

    private void appendCaveats(StringBuilder text, Forecast forecast) {
        if (forecast.seasonalitySuspected()) {
            appendSeasonalCaveat(text, forecast);
            return;
        }
        appendLowConfidenceCaveat(text, forecast);
    }

    private void appendLowConfidenceCaveat(StringBuilder text, Forecast forecast) {
        if (forecast.confidence() == null
                || forecast.confidence().compareTo(LOW_CONFIDENCE_THRESHOLD) >= 0) {
            return;
        }

        boolean thin = forecast.selection().nonZeroEligibleDays() < THIN_EVIDENCE_DAYS;
        boolean erratic = forecast.avgDailyDemand().signum() > 0
                && forecast.demandStddev().divide(forecast.avgDailyDemand(), DemandSeries.MC)
                        .compareTo(ERRATIC_VARIATION) > 0;

        text.append(" Treat this as a rough estimate rather than a firm one");
        if (thin && erratic) {
            text.append(" — this product has only sold on ")
                    .append(forecast.selection().nonZeroEligibleDays())
                    .append(" days in the period we looked at, and how much it sells swings a "
                            + "lot from one day to the next");
        } else if (thin) {
            text.append(" — this product has only sold on ")
                    .append(forecast.selection().nonZeroEligibleDays())
                    .append(" days in the period we looked at, so there is not a lot to go on "
                            + "yet");
        } else if (erratic) {
            text.append(" — how much this product sells swings a lot from one day to the next, "
                    + "so any single figure will be wide of the mark some weeks");
        }
        text.append(". It will sharpen up as more sales come in; until then your own sense of "
                + "this product is worth as much as the number.");
    }

    private void appendSeasonalCaveat(StringBuilder text, Forecast forecast) {
        if (!forecast.seasonalitySuspected()) {
            return;
        }
        text.append(" Note: this product's sales repeat on a regular pattern — busier at some "
                + "times than others — and this estimate averages the busy and quiet stretches "
                + "together, so it cannot fully account for that pattern. Treat it as a rough "
                + "guide: you will likely need more than this going into a busy stretch, and "
                + "less coming out of one.");
    }

    public enum Urgency {
        CRITICAL("critical"), HIGH("high"), NORMAL("normal");

        private final String wireValue;

        Urgency(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    static String friendly(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        BigDecimal rounded = value.abs().compareTo(BigDecimal.TEN) >= 0
                ? value.setScale(0, RoundingMode.HALF_UP)
                : value.setScale(1, RoundingMode.HALF_UP);
        return rounded.stripTrailingZeros().toPlainString();
    }

    private static boolean isOne(BigDecimal value) {
        return value.setScale(1, RoundingMode.HALF_UP).compareTo(BigDecimal.ONE) == 0;
    }
}
