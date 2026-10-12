CREATE OR REPLACE VIEW v_stock_status WITH (security_invoker = true) AS
SELECT
    ps.tenant_id,
    ps.product_id,
    ps.location_id,
    p.sku,
    p.name,
    ps.quantity_on_hand,
    ps.quantity_available,
    COALESCE(p.reorder_point, f.reorder_point)          AS effective_reorder_point,
    f.avg_daily_demand,
    f.days_of_cover,
    f.projected_stockout_on,
    CASE
        WHEN ps.quantity_on_hand <= 0                                        THEN 'out_of_stock'
        WHEN COALESCE(p.reorder_point, f.reorder_point) IS NULL              THEN 'ok'
        WHEN ps.quantity_available <= COALESCE(p.reorder_point, f.reorder_point) THEN 'reorder'
        ELSE 'ok'
    END AS stock_state
FROM product_stock ps
JOIN products p
     ON p.tenant_id = ps.tenant_id AND p.id = ps.product_id
LEFT JOIN forecasts f
     ON f.tenant_id = ps.tenant_id
    AND f.product_id = ps.product_id
    AND f.location_id = ps.location_id
    AND f.is_current
WHERE p.deleted_at IS NULL AND p.is_tracked;
