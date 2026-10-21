/* =====================================================================
   ROYAL - V19: a cancelled invoice / settlement no longer blocks its replacement

   Before: uq_invoice_order (UNIQUE order_id) and uq_remittance_item_order (UNIQUE order_id) were
   unfiltered, so once an invoice or a COD settlement was cancelled its order could never get a
   new one.

   After:
     - an order has at most ONE non-cancelled invoice, and any number of cancelled ones;
     - an order is in at most ONE active settlement, and any number of cancelled ones.

   What does NOT change: uq_invoice_number and uq_invoice_year_seq. A cancelled invoice keeps its
   number forever and the sequence stays gap-free; a replacement takes the next number.

   Adds:
     invoice.replaces_invoice_id          the cancelled invoice a new one replaces (nullable)
     cod_remittance_item.is_active        0 once the item's settlement is cancelled; existing items of
                                          cancelled settlements are backfilled to 0

   FILTERED INDEXES need the same SET options on every session that later writes to the table
   (error 1934 otherwise). They are all stated below. The application's own connection is
   checked by SessionOptionsIntegrationTest.

   Safe on a database that already holds rows: both new unique indexes cover a SUBSET of what the
   old ones covered, so existing data cannot violate them; the pre-flight checks below still stop
   the script, before anything is changed, if it somehow does. Everything runs in one transaction,
   so a failure leaves the schema exactly as it was. Safe to run again.

   Apply with:  sqlcmd -d <database> -f 65001 -b -i V19__replaceable_invoice_and_settlement.sql
   ===================================================================== */

SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
SET ANSI_WARNINGS ON;
SET ARITHABORT ON;
SET CONCAT_NULL_YIELDS_NULL ON;
SET NUMERIC_ROUNDABORT OFF;
SET XACT_ABORT ON;
GO

BEGIN TRY
    BEGIN TRANSACTION;

    /* ---------------------------------------------------------------
       0. Pre-flight: stop before touching anything if the data already
          breaks the rules the new indexes enforce.
       --------------------------------------------------------------- */
    IF EXISTS (SELECT 1 FROM dbo.invoice WHERE status <> 'CANCELLED'
               GROUP BY order_id HAVING COUNT(*) > 1)
        THROW 51901, 'V19 aborted: an order has more than one non-cancelled invoice.', 1;

    IF EXISTS (SELECT 1 FROM dbo.cod_remittance_item i
               JOIN dbo.cod_remittance r ON r.id = i.remittance_id
               WHERE r.status <> 'CANCELLED'
               GROUP BY i.order_id HAVING COUNT(*) > 1)
        THROW 51902, 'V19 aborted: an order is in more than one active settlement.', 1;

    /* ---------------------------------------------------------------
       1. invoice.replaces_invoice_id
          (dynamic SQL: the column must exist before the constraint that names it is compiled)
       --------------------------------------------------------------- */
    IF COL_LENGTH('dbo.invoice', 'replaces_invoice_id') IS NULL
        EXEC('ALTER TABLE dbo.invoice ADD replaces_invoice_id BIGINT NULL');

    IF NOT EXISTS (SELECT 1 FROM sys.foreign_keys WHERE name = 'fk_invoice_replaces'
                   AND parent_object_id = OBJECT_ID('dbo.invoice'))
        EXEC('ALTER TABLE dbo.invoice ADD CONSTRAINT fk_invoice_replaces
              FOREIGN KEY (replaces_invoice_id) REFERENCES dbo.invoice(id)');

    /* ---------------------------------------------------------------
       2. uq_invoice_order: unique among NON-cancelled invoices only.
          It is an index in V3 and in the baseline; if some database declared it as a UNIQUE
          constraint instead, DROP INDEX would fail, so each form gets its own statement.
       --------------------------------------------------------------- */
    IF EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'uq_invoice_order'
               AND object_id = OBJECT_ID('dbo.invoice') AND has_filter = 0)
    BEGIN
        IF EXISTS (SELECT 1 FROM sys.key_constraints WHERE name = 'uq_invoice_order'
                   AND parent_object_id = OBJECT_ID('dbo.invoice') AND type = 'UQ')
            ALTER TABLE dbo.invoice DROP CONSTRAINT uq_invoice_order;
        ELSE
            DROP INDEX uq_invoice_order ON dbo.invoice;
    END

    IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'uq_invoice_order'
                   AND object_id = OBJECT_ID('dbo.invoice'))
        EXEC('CREATE UNIQUE NONCLUSTERED INDEX uq_invoice_order
              ON dbo.invoice(order_id) WHERE status <> ''CANCELLED''');

    /* Cancelled invoices are looked up by order too (history), and the filtered index above
       does not cover them. */
    IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ix_invoice_order_id'
                   AND object_id = OBJECT_ID('dbo.invoice'))
        CREATE NONCLUSTERED INDEX ix_invoice_order_id ON dbo.invoice(order_id);

    /* ---------------------------------------------------------------
       3. cod_remittance_item.is_active, backfilled from the settlement's status
       --------------------------------------------------------------- */
    IF COL_LENGTH('dbo.cod_remittance_item', 'is_active') IS NULL
    BEGIN
        EXEC('ALTER TABLE dbo.cod_remittance_item
              ADD is_active BIT NOT NULL CONSTRAINT df_remit_item_active DEFAULT 1');
        EXEC('UPDATE i SET is_active = 0
              FROM dbo.cod_remittance_item i
              JOIN dbo.cod_remittance r ON r.id = i.remittance_id
              WHERE r.status = ''CANCELLED''');
    END

    IF EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'uq_remittance_item_order'
               AND object_id = OBJECT_ID('dbo.cod_remittance_item') AND has_filter = 0)
    BEGIN
        IF EXISTS (SELECT 1 FROM sys.key_constraints WHERE name = 'uq_remittance_item_order'
                   AND parent_object_id = OBJECT_ID('dbo.cod_remittance_item') AND type = 'UQ')
            ALTER TABLE dbo.cod_remittance_item DROP CONSTRAINT uq_remittance_item_order;
        ELSE
            DROP INDEX uq_remittance_item_order ON dbo.cod_remittance_item;
    END

    IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'uq_remittance_item_order'
                   AND object_id = OBJECT_ID('dbo.cod_remittance_item'))
        EXEC('CREATE UNIQUE NONCLUSTERED INDEX uq_remittance_item_order
              ON dbo.cod_remittance_item(order_id) WHERE is_active = 1');

    COMMIT TRANSACTION;
END TRY
BEGIN CATCH
    IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;   -- the schema is left unchanged
    THROW;
END CATCH
GO
