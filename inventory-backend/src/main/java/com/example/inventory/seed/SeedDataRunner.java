package com.example.inventory.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("seed")
public class SeedDataRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedDataRunner.class);

    public static final int WINDOW_WEEKS = 30;

    private final TenantSeeder seeder;
    private final ConfigurableApplicationContext context;

    public SeedDataRunner(TenantSeeder seeder, ConfigurableApplicationContext context) {
        this.seeder = seeder;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        long tag = System.currentTimeMillis() % 1_000_000_000L;
        log.info("M6 seed data: generating {} weeks of history for two tenants (tag {})", WINDOW_WEEKS, tag);

        var tenantA = seeder.seedTenant("Riverside Bakery", "riverside-" + tag, tag, WINDOW_WEEKS);
        log.info("Seeded tenant A: {} (slug riverside-{}), products: {}",
                tenantA.tenantId(), tag, tenantA.productIdsByShape());

        var tenantB = seeder.seedTenant("Harborview Deli", "harborview-" + tag, tag + 1, WINDOW_WEEKS);
        log.info("Seeded tenant B: {} (slug harborview-{}), products: {}",
                tenantB.tenantId(), tag, tenantB.productIdsByShape());

        log.info("M6 seed data complete. Owner login: owner+riverside-{}@example.test / "
                + "owner+harborview-{}@example.test, password SeedPassw0rd!1", tag, tag);

        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
