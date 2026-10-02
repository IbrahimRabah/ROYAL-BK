/* =====================================================================
   VELORA — V9: estimated rate for the REMOTE shipping zone

   V2 flagged this with "REVIEW THIS once a courier is chosen" and priced
   North Sinai, South Sinai, Red Sea, New Valley and Matrouh at a guessed
   100 EGP / 2-7 days — the same number as Upper Egypt, which is not a
   real distinction.

   ESTIMATES, not courier quotes: no courier contract has been signed, so
   there is no real source for these numbers. REMOTE is priced as one tier
   for all five governorates. Replace them with real figures once a courier
   is contracted. Run once against the `velora` database. Safe to re-run.
   ===================================================================== */

USE velora;
GO

SET QUOTED_IDENTIFIER ON;
GO

UPDATE r
SET r.base_cost = 150.0000,
    r.delivery_days_min = 3,
    r.delivery_days_max = 8
FROM shipping_rate r
JOIN shipping_zone z ON z.id = r.zone_id
WHERE z.code = 'REMOTE';
GO

/* ---------------------------------------------------------------------
   Verify — every frontier governorate must resolve to the new rate.
   --------------------------------------------------------------------- */

SELECT g.code AS governorate,
       r.base_cost AS shipping_cost,
       CAST(r.delivery_days_min AS VARCHAR) + '-'
           + CAST(r.delivery_days_max AS VARCHAR) + ' days' AS delivery_time
FROM governorate g
JOIN shipping_zone_governorate zg ON zg.governorate_id = g.id
JOIN shipping_zone z ON z.id = zg.zone_id
JOIN shipping_rate r ON r.zone_id = z.id
WHERE z.code = 'REMOTE'
ORDER BY g.display_order;
GO

PRINT 'REMOTE zone shipping rate updated: 150 EGP, 3-8 days.';
GO
