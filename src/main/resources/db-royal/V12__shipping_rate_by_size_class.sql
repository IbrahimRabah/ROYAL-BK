/* =====================================================================
   VELORA - V12: shipping priced per (zone, size class), with a zone cap

   Before: one price per zone.
   After:  one price per (zone, size class) per unit; shipping for an order
           is the sum of unit price x quantity over its lines, capped at the
           zone's max_shipping_cost.

   - shipping_rate.size_class (SMALL / MEDIUM / LARGE); unique per zone.
     shipping_rate.base_cost now means "cost of ONE unit of that size".
   - shipping_zone.max_shipping_cost (nullable): ceiling on the item total.
   - customer_order gains the shipping snapshot: breakdown, whether the cap
     applied, and the uncapped figure. Orders placed before this stay NULL.
   - Alexandria moves out of DELTA into ALEXANDRIA; Port Said, Ismailia and
     Suez move into CANAL. Both zones are re-activated (V10 had hidden them
     because they were empty).

   Unused from now on, but left in place: shipping_rate.max_weight_grams,
   cost_per_extra_kg, free_shipping_over. Greater Cairo is free through its
   0 prices, not through free_shipping_over.

   !! REVIEW: the REMOTE prices (200 / 400 / 700) are an ESTIMATE, set above
   !! Upper Egypt because the zone is further away. They are not from a
   !! courier quote. Revisit them when a shipping company is contracted.

   cod_fee and delivery_days_* stay on the rate rows; the admin API writes the
   same value to every size row of a zone.

   Safe to run again.
   ===================================================================== */

SET QUOTED_IDENTIFIER ON;
GO

IF NOT EXISTS (SELECT 1 FROM sys.columns
               WHERE object_id = OBJECT_ID('dbo.shipping_zone') AND name = 'max_shipping_cost')
BEGIN
    ALTER TABLE dbo.shipping_zone ADD max_shipping_cost DECIMAL(19,4) NULL;
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.columns
               WHERE object_id = OBJECT_ID('dbo.shipping_rate') AND name = 'size_class')
BEGIN
    ALTER TABLE dbo.shipping_rate ADD size_class VARCHAR(10) NULL;
END
GO

/* The one existing row per zone becomes the SMALL row ... */
UPDATE dbo.shipping_rate SET size_class = 'SMALL' WHERE size_class IS NULL;
GO

/* ... and MEDIUM / LARGE are copied from it, keeping days and COD fee. */
INSERT INTO dbo.shipping_rate
    (zone_id, base_cost, max_weight_grams, cost_per_extra_kg, free_shipping_over,
     cod_fee, delivery_days_min, delivery_days_max, is_active, created_at, size_class)
SELECT s.zone_id, s.base_cost, s.max_weight_grams, s.cost_per_extra_kg, s.free_shipping_over,
       s.cod_fee, s.delivery_days_min, s.delivery_days_max, s.is_active,
       SYSDATETIMEOFFSET(), v.size_class
FROM dbo.shipping_rate s
CROSS JOIN (VALUES ('MEDIUM'), ('LARGE')) v(size_class)
WHERE s.size_class = 'SMALL'
  AND NOT EXISTS (SELECT 1 FROM dbo.shipping_rate x
                  WHERE x.zone_id = s.zone_id AND x.size_class = v.size_class);
GO

ALTER TABLE dbo.shipping_rate ALTER COLUMN size_class VARCHAR(10) NOT NULL;
GO

IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'CK_shipping_rate_size_class')
BEGIN
    ALTER TABLE dbo.shipping_rate ADD CONSTRAINT CK_shipping_rate_size_class
        CHECK (size_class IN ('SMALL', 'MEDIUM', 'LARGE'));
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes
               WHERE name = 'UX_shipping_rate_zone_size'
                 AND object_id = OBJECT_ID('dbo.shipping_rate'))
BEGIN
    CREATE UNIQUE INDEX UX_shipping_rate_zone_size ON dbo.shipping_rate (zone_id, size_class);
END
GO

/* ---------------------------------------------------------------------
   Zones: re-home governorates, re-activate ALEXANDRIA and CANAL
   --------------------------------------------------------------------- */

UPDATE zg
SET zg.zone_id = (SELECT id FROM dbo.shipping_zone WHERE code = 'ALEXANDRIA')
FROM dbo.shipping_zone_governorate zg
JOIN dbo.governorate g ON g.id = zg.governorate_id
WHERE g.code = 'ALX';
GO

UPDATE zg
SET zg.zone_id = (SELECT id FROM dbo.shipping_zone WHERE code = 'CANAL')
FROM dbo.shipping_zone_governorate zg
JOIN dbo.governorate g ON g.id = zg.governorate_id
WHERE g.code IN ('PTS', 'ISM', 'SUZ');
GO

UPDATE dbo.shipping_zone SET is_active = 1 WHERE code IN ('ALEXANDRIA', 'CANAL');
GO

UPDATE r SET r.is_active = 1
FROM dbo.shipping_rate r JOIN dbo.shipping_zone z ON z.id = r.zone_id
WHERE z.code IN ('ALEXANDRIA', 'CANAL');
GO

/* ---------------------------------------------------------------------
   Prices (EGP per unit) and the cap
   --------------------------------------------------------------------- */

UPDATE r
SET r.base_cost = p.cost
FROM dbo.shipping_rate r
JOIN dbo.shipping_zone z ON z.id = r.zone_id
JOIN (VALUES
    ('GREATER_CAIRO', 'SMALL',   0.0000), ('GREATER_CAIRO', 'MEDIUM',   0.0000), ('GREATER_CAIRO', 'LARGE',   0.0000),
    ('DELTA',         'SMALL', 100.0000), ('DELTA',         'MEDIUM', 200.0000), ('DELTA',         'LARGE', 400.0000),
    ('ALEXANDRIA',    'SMALL', 120.0000), ('ALEXANDRIA',    'MEDIUM', 250.0000), ('ALEXANDRIA',    'LARGE', 450.0000),
    ('CANAL',         'SMALL', 120.0000), ('CANAL',         'MEDIUM', 250.0000), ('CANAL',         'LARGE', 450.0000),
    ('UPPER_EGYPT',   'SMALL', 150.0000), ('UPPER_EGYPT',   'MEDIUM', 300.0000), ('UPPER_EGYPT',   'LARGE', 550.0000),
    -- REMOTE: estimate, see the REVIEW note in the header.
    ('REMOTE',        'SMALL', 200.0000), ('REMOTE',        'MEDIUM', 400.0000), ('REMOTE',        'LARGE', 700.0000)
) p(zone_code, size_class, cost)
  ON p.zone_code = z.code AND p.size_class = r.size_class;
GO

UPDATE dbo.shipping_zone SET max_shipping_cost = 1500.0000;
GO

/* ---------------------------------------------------------------------
   Order snapshot of the shipping calculation. NULL on older orders.
   --------------------------------------------------------------------- */

IF NOT EXISTS (SELECT 1 FROM sys.columns
               WHERE object_id = OBJECT_ID('dbo.customer_order') AND name = 'shipping_breakdown')
BEGIN
    ALTER TABLE dbo.customer_order ADD shipping_breakdown VARCHAR(500) NULL;
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.columns
               WHERE object_id = OBJECT_ID('dbo.customer_order') AND name = 'shipping_cap_applied')
BEGIN
    ALTER TABLE dbo.customer_order ADD shipping_cap_applied BIT NULL;
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.columns
               WHERE object_id = OBJECT_ID('dbo.customer_order') AND name = 'shipping_uncapped_cost')
BEGIN
    ALTER TABLE dbo.customer_order ADD shipping_uncapped_cost DECIMAL(19,4) NULL;
END
GO

/* ---------------------------------------------------------------------
   Verify
   --------------------------------------------------------------------- */

SELECT z.code, z.is_active, z.max_shipping_cost,
       MAX(CASE WHEN r.size_class = 'SMALL'  THEN r.base_cost END) AS small_cost,
       MAX(CASE WHEN r.size_class = 'MEDIUM' THEN r.base_cost END) AS medium_cost,
       MAX(CASE WHEN r.size_class = 'LARGE'  THEN r.base_cost END) AS large_cost,
       (SELECT COUNT(*) FROM dbo.shipping_zone_governorate zg WHERE zg.zone_id = z.id) AS governorates
FROM dbo.shipping_zone z
LEFT JOIN dbo.shipping_rate r ON r.zone_id = z.id
GROUP BY z.id, z.code, z.is_active, z.max_shipping_cost
ORDER BY z.id;
GO
