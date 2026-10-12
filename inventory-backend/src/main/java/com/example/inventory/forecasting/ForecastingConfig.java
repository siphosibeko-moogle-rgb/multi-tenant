package com.example.inventory.forecasting;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ForecastingProperties.class)
public class ForecastingConfig {
}
