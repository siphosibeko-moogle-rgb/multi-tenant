package com.example.inventory.forecasting;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.forecasting")
public record ForecastingProperties(
        @DefaultValue("365") int historyWindowDays,
        @DefaultValue("30") int horizonDays) {

    public static final int DEFAULT_WINDOW_DAYS = 365;

    public static final int DEFAULT_HORIZON_DAYS = 30;

    public ForecastingProperties {
        if (horizonDays <= 0) {
            throw new IllegalArgumentException(
                    "app.forecasting.horizon-days must be positive; got " + horizonDays);
        }
        if (historyWindowDays < MethodSelector.MIN_HISTORY_DAYS) {
            throw new IllegalArgumentException(
                    "app.forecasting.history-window-days must be at least "
                            + MethodSelector.MIN_HISTORY_DAYS + " (ADR §5's readiness floor) "
                            + "or no product could ever become ready; got " + historyWindowDays);
        }
    }
}
