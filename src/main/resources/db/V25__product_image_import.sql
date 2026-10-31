/* =====================================================================
   ROYAL - V25: bulk image import (content hash + run id on product_image)

   The "Import images" screen attaches pictures to products by SKU, in many small requests.
   Two columns make that safe to run, and to re-run:

     content_hash     SHA-256 of the stored bytes (hex). The same picture is never attached to
                      the same product twice, however many times the folder is uploaded.
                      Unique per product WHERE NOT NULL: images uploaded one by one from the
                      product form have no hash and are not constrained.
     import_run_id    the id the screen generates once per upload run. Lets a run's own images
                      be told apart from the ones that were there before it: "skip products that
                      already have images" must not count the images this same run just added
                      when its files arrive in separate requests, and "replace" must remove only
                      what was there before the run.

   Existing rows keep both NULL. Nothing is rewritten.

   Why V25 and not V24: an earlier V24 was applied by hand to royal and royal_test and then
   removed again (its column dropped by hand). The databases carry no migration ledger, so that
   number cannot be proven clean; V24 stays unused on purpose.

   Re-runnable: every step checks whether it is already done. One transaction.
   Apply with:  sqlcmd -d <database> -f 65001 -b -i V25__product_image_import.sql
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

    IF COL_LENGTH('dbo.product_image', 'content_hash') IS NULL
        ALTER TABLE dbo.product_image ADD content_hash CHAR(64) NULL;

    IF COL_LENGTH('dbo.product_image', 'import_run_id') IS NULL
        ALTER TABLE dbo.product_image ADD import_run_id VARCHAR(36) NULL;

    COMMIT TRANSACTION;
END TRY
BEGIN CATCH
    IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;
    THROW;
END CATCH;
GO

-- Indexes reference the new columns, so they go in a batch of their own (the columns must exist
-- when the batch is compiled).
BEGIN TRY
    BEGIN TRANSACTION;

    IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'uq_img_product_hash'
                   AND object_id = OBJECT_ID('dbo.product_image'))
        CREATE UNIQUE INDEX uq_img_product_hash ON dbo.product_image (product_id, content_hash)
            WHERE content_hash IS NOT NULL;

    IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ix_img_run'
                   AND object_id = OBJECT_ID('dbo.product_image'))
        CREATE INDEX ix_img_run ON dbo.product_image (import_run_id)
            WHERE import_run_id IS NOT NULL;

    COMMIT TRANSACTION;
END TRY
BEGIN CATCH
    IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;
    THROW;
END CATCH;
GO
