USE velora;
GO

/* =====================================================================
   VELORA - V13: stop delivering to South Sinai, Aswan and Luxor

   A governorate is "served" when it belongs to a shipping zone that has
   rates. Closing one means removing its row from shipping_zone_governorate:
   the governorate stays in the governorate table and stays active, so the
   address form and GET /geo/governorates still list it, with served = false.
   (Setting governorate.is_active = 0 would hide it instead.)

   !! SOUTH SINAI is closed as a whole. It is the only governorate that holds
   !! Sharm El-Sheikh, and the schema has no city level, so Sharm cannot be
   !! closed on its own. Dahab, Nuweiba, Taba, Ras Sudr and Saint Catherine are
   !! closed with it - a deliberate decision, because none of them has better
   !! courier coverage than Sharm.

   Reopening is done from the admin API, not SQL:
     PUT /api/v1/admin/shipping/governorates/{governorateId}/zone

   Safe to run again.
   ===================================================================== */

SET QUOTED_IDENTIFIER ON;
GO

DELETE zg
FROM dbo.shipping_zone_governorate zg
JOIN dbo.governorate g ON g.id = zg.governorate_id
WHERE g.code IN ('SSI', 'ASW', 'LUX');
GO

/* ---------------------------------------------------------------------
   Verify - the three must show no zone
   --------------------------------------------------------------------- */

SELECT g.code, g.name_en, g.is_active, z.code AS zone_code
FROM dbo.governorate g
LEFT JOIN dbo.shipping_zone_governorate zg ON zg.governorate_id = g.id
LEFT JOIN dbo.shipping_zone z ON z.id = zg.zone_id
WHERE g.code IN ('SSI', 'ASW', 'LUX');
GO
