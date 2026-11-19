-- SAH-78 (WP-B, java-developer follow-up): product search matches lower(barcode) = :q on the global catalogue
-- (pharmacy.catalogue_barcode, about 300,000 rows). The unique key uq_catalogue_barcode_barcode is on the raw
-- (case-sensitive) barcode, so it cannot serve that lookup. Additive: one new index, no data change.
-- The tenant side (pharmacy.product.barcode) needs no new index: uq_product_barcode on (tenant_id, unit_id, lower(barcode))
-- already serves it (tenant_id leads, and every request is one tenant under RLS).
create index if not exists ix_catalogue_barcode_lower
  on pharmacy.catalogue_barcode (lower(barcode));
