package com.example.inventory.tenancy;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.zaxxer.hikari.HikariDataSource;

@Configuration(proxyBeanMethods = false)
public class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    DataSourceProperties appDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource appConnectionPool(
            @Qualifier("appDataSourceProperties") DataSourceProperties appDataSourceProperties) {
        return appDataSourceProperties
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    @Primary
    DataSource appDataSource(@Qualifier("appConnectionPool") HikariDataSource appConnectionPool) {
        return new TenantConnectionProvider(appConnectionPool);
    }

    @Bean
    @ConfigurationProperties("app.datasource.login")
    DataSourceProperties loginDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("app.datasource.login.hikari")
    HikariDataSource loginDataSource(
            @Qualifier("loginDataSourceProperties") DataSourceProperties loginDataSourceProperties) {
        return loginDataSourceProperties
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }
}
