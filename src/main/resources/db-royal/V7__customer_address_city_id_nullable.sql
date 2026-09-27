/* =====================================================================
   VELORA â€” V7: customer_address.city_id becomes nullable

   Run once against the `velora` database.

   The business decision documented in the API contract is that a saved
   address captures the city as free text (folded into `area`) and the
   governorate alone drives shipping â€” `city_id` was never meant to be a
   required foreign key. The column was left over from an earlier design
   and is still `BIGINT NOT NULL`, so every `POST /api/v1/me/addresses`
   fails with a NOT NULL violation: the request has no `cityId` field to
   populate it with.

   This script relaxes the constraint the code already assumes. The
   `fk_addr_city` foreign key is left in place â€” NULL values never
   participate in a foreign key check, so a customer who has no city
   reference row simply leaves the column NULL.

   Safe to run again on a database where it already applied.
   ===================================================================== */

GO

IF EXISTS (
    SELECT 1 FROM sys.columns
    WHERE object_id = OBJECT_ID('dbo.customer_address')
      AND name = 'city_id'
      AND is_nullable = 0
)
BEGIN
    ALTER TABLE customer_address ALTER COLUMN city_id BIGINT NULL;
END
GO

/* ---------------------------------------------------------------------
   Verify
   --------------------------------------------------------------------- */

SELECT c.name, c.is_nullable
FROM sys.columns c
WHERE c.object_id = OBJECT_ID('dbo.customer_address') AND c.name = 'city_id';
GO

PRINT 'customer_address.city_id is now nullable. Addresses can be saved without a city reference.';
GO

