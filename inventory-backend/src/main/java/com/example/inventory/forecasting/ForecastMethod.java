package com.example.inventory.forecasting;

public enum ForecastMethod {

    MOVING_AVERAGE("moving_average"),

    WEIGHTED_MOVING_AVERAGE("weighted_moving_average"),

    EXPONENTIAL_SMOOTHING("exponential_smoothing"),

    CROSTON("croston"),

    ML_MODEL("ml_model"),

    NAIVE("naive"),

    INSUFFICIENT_DATA("insufficient_data");

    private final String dbValue;

    ForecastMethod(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static ForecastMethod fromDbValue(String value) {
        for (ForecastMethod method : values()) {
            if (method.dbValue.equals(value)) {
                return method;
            }
        }
        throw new IllegalArgumentException("Unknown forecast_method: " + value);
    }
}
