package com.example.inventory.forecasting;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class LeadTimeResolver {

    static final int MIN_OBSERVATIONS = 5;

    private final JdbcTemplate jdbc;

    public LeadTimeResolver(@Qualifier("appDataSource") DataSource appDataSource) {
        this.jdbc = new JdbcTemplate(appDataSource);
    }

    public enum Source {
        OBSERVED,
        PROMISED_FOR_PRODUCT,
        PROMISED_BY_SUPPLIER
    }

    public record LeadTime(UUID supplierId, String supplierName, BigDecimal days,
                          Source source, int sampleSize) {

        public boolean isObserved() {
            return source == Source.OBSERVED;
        }
    }

    public Optional<LeadTime> forProduct(UUID productId) {
        List<PreferredSupplier> candidates = jdbc.query("""
                SELECT ps.supplier_id,
                       s.name                AS supplier_name,
                       ps.lead_time_days     AS product_lead_time,
                       s.lead_time_days      AS supplier_lead_time
                FROM product_suppliers ps
                JOIN suppliers s
                  ON s.tenant_id = ps.tenant_id AND s.id = ps.supplier_id
                WHERE ps.product_id = ?
                  AND s.deleted_at IS NULL
                  AND s.is_active
                ORDER BY ps.is_preferred DESC, ps.created_at
                """,
                (rs, rowNum) -> new PreferredSupplier(
                        rs.getObject("supplier_id", UUID.class),
                        rs.getString("supplier_name"),
                        rs.getObject("product_lead_time", Integer.class),
                        rs.getInt("supplier_lead_time")),
                productId);

        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        PreferredSupplier chosen = candidates.get(0);

        Observations observed = observationsFor(chosen.supplierId());
        if (observed.count() >= MIN_OBSERVATIONS) {
            return Optional.of(new LeadTime(chosen.supplierId(), chosen.supplierName(),
                    observed.averageDays(), Source.OBSERVED, observed.count()));
        }
        if (chosen.productLeadTime() != null) {
            return Optional.of(new LeadTime(chosen.supplierId(), chosen.supplierName(),
                    BigDecimal.valueOf(chosen.productLeadTime()),
                    Source.PROMISED_FOR_PRODUCT, observed.count()));
        }
        return Optional.of(new LeadTime(chosen.supplierId(), chosen.supplierName(),
                BigDecimal.valueOf(chosen.supplierLeadTime()),
                Source.PROMISED_BY_SUPPLIER, observed.count()));
    }

    public Observations observationsFor(UUID supplierId) {
        return jdbc.queryForObject("""
                SELECT count(*)                          AS n,
                       COALESCE(avg(lead_time_days), 0)  AS average_days
                FROM supplier_lead_time_observations
                WHERE supplier_id = ?
                """,
                (rs, rowNum) -> new Observations(rs.getInt("n"), rs.getBigDecimal("average_days")),
                supplierId);
    }

    public record Observations(int count, BigDecimal averageDays) {
    }

    private record PreferredSupplier(UUID supplierId, String supplierName,
                                     Integer productLeadTime, int supplierLeadTime) {
    }
}
