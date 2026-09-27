/* =====================================================================
   VELORA â€” V4: audit log

   Run once against the `velora` database.

   The table already exists from V1 but nothing ever wrote to it, and its
   shape does not match what the application now records. It is empty, so
   recreating it loses nothing â€” the script checks that and refuses if it
   is wrong.
   ===================================================================== */

GO

/* Refuse if anything is in there. An audit log is evidence; dropping one
   with rows in it destroys the only record of who did what. */
IF OBJECT_ID('dbo.audit_log', 'U') IS NOT NULL
   AND EXISTS (SELECT 1 FROM audit_log)
BEGIN
    RAISERROR ('REFUSING TO RUN: audit_log already contains rows. Dropping it would destroy the record of who changed what.', 16, 1);
    RETURN;
END
GO

/* Drop any foreign keys pointing at it first â€” V1 may have wired some. */
DECLARE @drop NVARCHAR(MAX) = N'';
SELECT @drop = @drop + 'ALTER TABLE ' + QUOTENAME(OBJECT_SCHEMA_NAME(parent_object_id))
    + '.' + QUOTENAME(OBJECT_NAME(parent_object_id))
    + ' DROP CONSTRAINT ' + QUOTENAME(name) + ';' + CHAR(10)
FROM sys.foreign_keys
WHERE referenced_object_id = OBJECT_ID('dbo.audit_log');

IF LEN(@drop) > 0
BEGIN
    PRINT 'Dropping foreign keys that reference audit_log:';
    PRINT @drop;
    EXEC sp_executesql @drop;
END
GO

DROP TABLE IF EXISTS audit_log;
GO

/* No foreign keys, deliberately.

   Audit rows outlive what they describe. A product gets archived, a staff
   account gets removed, and the record of what they did has to survive
   both â€” a foreign key would either block that or cascade the evidence
   away. The entity type and id are stored as plain values, with a human
   label alongside so the row reads without joining anything. */
CREATE TABLE audit_log (
    id            BIGINT IDENTITY(1,1) PRIMARY KEY,
    [action]      VARCHAR(40)    NOT NULL,
    entity_type   VARCHAR(40)    NOT NULL,
    entity_id     NVARCHAR(60)   NULL,
    entity_label  NVARCHAR(200)  NULL,
    old_value     NVARCHAR(500)  NULL,
    new_value     NVARCHAR(500)  NULL,
    reason        NVARCHAR(500)  NULL,
    actor_id      BIGINT         NULL,
    actor_name    NVARCHAR(150)  NULL,
    created_at    DATETIMEOFFSET NOT NULL DEFAULT SYSDATETIMEOFFSET()
);
GO

/* The three ways this table actually gets read. */
CREATE INDEX ix_audit_created   ON audit_log(created_at DESC);
CREATE INDEX ix_audit_entity    ON audit_log(entity_type, entity_id);
CREATE INDEX ix_audit_actor     ON audit_log(actor_id, created_at DESC);
CREATE INDEX ix_audit_action    ON audit_log([action], created_at DESC);
GO

/* ---------------------------------------------------------------------
   Verify
   --------------------------------------------------------------------- */

SELECT COUNT(*) AS audit_log_ready
FROM sys.tables WHERE name = 'audit_log';

SELECT COUNT(*) AS index_count
FROM sys.indexes
WHERE object_id = OBJECT_ID('dbo.audit_log') AND name IS NOT NULL;
GO

PRINT 'Audit log ready. Price changes and stock adjustments are recorded from now on.';
GO

