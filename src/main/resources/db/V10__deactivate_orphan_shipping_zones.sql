/* =====================================================================
   VELORA — V10: deactivate orphan shipping zones (ALEXANDRIA, CANAL)

   V1 pre-provisioned six zones so a future price split would be a data
   change, not a migration (see ShippingZone's javadoc). V2 then folded every
   governorate that would have used ALEXANDRIA and CANAL into DELTA instead,
   leaving those two zones with zero governorates.

   AdminShippingController's "list zones" endpoint returns every ACTIVE zone
   regardless of whether it has governorates, so the admin screen showed
   Alexandria twice: once as this empty zone, once as a governorate nested
   under Delta — with no way to tell which price actually applied.

   Deactivating rather than deleting: the day either needs its own rate again,
   reactivating it is a price change, not a migration — matching the original
   intent. Run once against the `velora` database. Safe to re-run.
   ===================================================================== */

USE velora;
GO

SET QUOTED_IDENTIFIER ON;
GO

UPDATE shipping_zone
SET is_active = 0
WHERE code IN ('ALEXANDRIA', 'CANAL');
GO

UPDATE r
SET r.is_active = 0
FROM shipping_rate r
JOIN shipping_zone z ON z.id = r.zone_id
WHERE z.code IN ('ALEXANDRIA', 'CANAL');
GO

/* ---------------------------------------------------------------------
   Verify — every active zone must have at least one governorate. Must
   return 0 rows.
   --------------------------------------------------------------------- */

SELECT z.code, COUNT(zg.governorate_id) AS governorate_count
FROM shipping_zone z
LEFT JOIN shipping_zone_governorate zg ON zg.zone_id = z.id
WHERE z.is_active = 1
GROUP BY z.code
HAVING COUNT(zg.governorate_id) = 0;
GO

/* ---------------------------------------------------------------------
   Verify — no governorate belongs to more than one zone. This is already
   guaranteed structurally (shipping_zone_governorate's primary key is
   governorate_id alone, not a zone_id+governorate_id composite, so a second
   INSERT for the same governorate is rejected outright) — this query just
   documents that the guarantee holds. Must return 0 rows.
   --------------------------------------------------------------------- */

SELECT governorate_id, COUNT(*) AS zone_count
FROM shipping_zone_governorate
GROUP BY governorate_id
HAVING COUNT(*) > 1;
GO

PRINT 'ALEXANDRIA and CANAL zones deactivated. Their governorates already lived in DELTA only.';
GO
