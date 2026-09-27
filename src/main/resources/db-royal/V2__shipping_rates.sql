/* =====================================================================
   VELORA â€” V2: shipping rates
   Flat pricing: 70 EGP Cairo + Lower Egypt, 100 EGP Upper Egypt.

   Run once against the `velora` database. Safe to re-run: it replaces
   the rate rows rather than adding to them.
   ===================================================================== */

GO

/* ---------------------------------------------------------------------
   1. Re-map governorates to two effective zones.

   The six zones stay in place â€” they are cheap to keep and expensive to
   introduce later. Today four of them simply share one price.
   --------------------------------------------------------------------- */

-- Cairo, Lower Egypt, Alexandria, Canal -> 70 EGP
UPDATE zg
SET zone_id = (SELECT id FROM shipping_zone WHERE code = 'DELTA')
FROM shipping_zone_governorate zg
JOIN governorate g ON g.id = zg.governorate_id
WHERE g.code IN ('ALX','BEH','GHR','MNF','DAK','SHR','KFS','DAM','PTS','ISM','SUZ');

-- Greater Cairo keeps its own zone (same price today, different tomorrow)
UPDATE zg
SET zone_id = (SELECT id FROM shipping_zone WHERE code = 'GREATER_CAIRO')
FROM shipping_zone_governorate zg
JOIN governorate g ON g.id = zg.governorate_id
WHERE g.code IN ('CAI','GIZ','QLY');

-- Upper Egypt -> 100 EGP
UPDATE zg
SET zone_id = (SELECT id FROM shipping_zone WHERE code = 'UPPER_EGYPT')
FROM shipping_zone_governorate zg
JOIN governorate g ON g.id = zg.governorate_id
WHERE g.code IN ('BNS','FYM','MNY','AST','SHG','QNA','LUX','ASW');

/* Frontier governorates are neither Delta nor Upper Egypt. Priced at 100
   for now â€” REVIEW THIS once a courier is chosen, because several of them
   cost noticeably more to reach. */
UPDATE zg
SET zone_id = (SELECT id FROM shipping_zone WHERE code = 'REMOTE')
FROM shipping_zone_governorate zg
JOIN governorate g ON g.id = zg.governorate_id
WHERE g.code IN ('NSI','SSI','RSY','WAD','MTR');
GO

/* ---------------------------------------------------------------------
   2. Replace the rates.
   --------------------------------------------------------------------- */

DELETE FROM shipping_rate;
GO

INSERT INTO shipping_rate
    (zone_id, base_cost, max_weight_grams, cost_per_extra_kg,
     free_shipping_over, cod_fee, delivery_days_min, delivery_days_max, is_active)
SELECT
    z.id,
    CASE z.code
        WHEN 'GREATER_CAIRO' THEN 70.0000
        WHEN 'DELTA'         THEN 70.0000
        WHEN 'ALEXANDRIA'    THEN 70.0000
        WHEN 'CANAL'         THEN 70.0000
        WHEN 'UPPER_EGYPT'   THEN 100.0000
        ELSE                      100.0000     -- REMOTE: review with the courier
    END,
    NULL,        -- weight ignored: flat rate
    0.0000,      -- no per-kilo surcharge
    NULL,        -- no free-shipping threshold
    0.0000,      -- no COD handling fee
    CASE z.code WHEN 'GREATER_CAIRO' THEN 1 ELSE 2 END,
    CASE z.code
        WHEN 'GREATER_CAIRO' THEN 3
        WHEN 'REMOTE'        THEN 7
        WHEN 'UPPER_EGYPT'   THEN 5
        ELSE                      4
    END,
    1
FROM shipping_zone z;
GO

/* ---------------------------------------------------------------------
   3. Verify â€” every governorate must resolve to exactly one rate.
   --------------------------------------------------------------------- */

SELECT g.name_ar AS Ø§Ù„Ù…Ø­Ø§ÙØ¸Ø©,
       z.name_ar AS Ø§Ù„Ù…Ù†Ø·Ù‚Ø©,
       r.base_cost AS Ø§Ù„Ø´Ø­Ù†,
       CAST(r.delivery_days_min AS VARCHAR) + '-'
           + CAST(r.delivery_days_max AS VARCHAR) + ' Ø£ÙŠØ§Ù…' AS Ø§Ù„Ù…Ø¯Ø©
FROM governorate g
JOIN shipping_zone_governorate zg ON zg.governorate_id = g.id
JOIN shipping_zone z ON z.id = zg.zone_id
JOIN shipping_rate r ON r.zone_id = z.id
ORDER BY r.base_cost, g.display_order;
GO

-- Must return 0. A governorate with no rate cannot be delivered to.
SELECT COUNT(*) AS governorates_without_a_rate
FROM governorate g
WHERE NOT EXISTS (
    SELECT 1 FROM shipping_zone_governorate zg
    JOIN shipping_rate r ON r.zone_id = zg.zone_id
    WHERE zg.governorate_id = g.id AND r.is_active = 1);
GO

PRINT 'Shipping rates applied: 70 EGP Cairo/Lower Egypt, 100 EGP Upper Egypt.';
GO

