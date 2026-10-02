/* =====================================================================
   VELORA — V8: stock_movement.actor_name

   Run once against the database you are upgrading (sqlcmd -d <database>).

   The movement ledger showed "#1" for every row — actorId with no name,
   unreadable once more than one staff member exists. audit_log already
   solved this with a name copied at write time (the account may be
   renamed or removed later, and the log must still say who did this);
   this brings the stock ledger to the same standard.

   Safe to run again on a database where it already applied.
   ===================================================================== */

IF NOT EXISTS (
    SELECT 1 FROM sys.columns
    WHERE object_id = OBJECT_ID('dbo.stock_movement') AND name = 'actor_name'
)
BEGIN
    ALTER TABLE stock_movement ADD actor_name NVARCHAR(150) NULL;
END
GO

/* ---------------------------------------------------------------------
   Verify
   --------------------------------------------------------------------- */

SELECT c.name, c.is_nullable, ty.name AS type_name, c.max_length / 2 AS char_length
FROM sys.columns c
JOIN sys.types ty ON ty.user_type_id = c.user_type_id
WHERE c.object_id = OBJECT_ID('dbo.stock_movement') AND c.name = 'actor_name';
GO

PRINT 'stock_movement.actor_name added. New movements will carry the actor''s name.';
GO
