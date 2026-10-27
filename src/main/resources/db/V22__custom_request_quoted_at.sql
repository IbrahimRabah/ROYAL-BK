/* =====================================================================
   ROYAL - V22: a custom request's quote has a date, and the customer's requests are indexed

   Adds custom_order_request.quoted_at - when the CURRENT quote amount was set. It moves when the
   amount changes (a revised price is a new quote with a new age) and not when the same amount is
   saved again. A quote with no date is a price with no age, and furniture prices go stale.

   Existing quoted requests are backfilled: the latest CUSTOM_REQUEST_QUOTED audit entry for the
   request, else the row's updated_at (the audit log is evidence, not a source of truth, so the
   row's own date is the fallback). Requests that were never quoted keep quoted_at NULL.

   Adds ix_custom_req_customer (customer_id, created_at DESC): the customer's own list and the
   "waiting for your answer" counter read by customer_id, which had no index.

   Writes nothing else. Re-runnable: each step checks whether it is already done, and the backfill
   only touches rows that still have no date. One transaction, so a failure leaves the schema and the
   data as they were. The statements that name the new column run as dynamic SQL because the column
   does not exist yet when the batch is compiled.

   Apply with:  sqlcmd -d <database> -f 65001 -b -i V22__custom_request_quoted_at.sql
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

    IF COL_LENGTH('dbo.custom_order_request', 'quoted_at') IS NULL
        EXEC('ALTER TABLE dbo.custom_order_request ADD quoted_at DATETIMEOFFSET(3) NULL');

    EXEC('UPDATE r
          SET quoted_at = COALESCE(
                (SELECT MAX(a.created_at) FROM dbo.audit_log a
                  WHERE a.action = ''CUSTOM_REQUEST_QUOTED''
                    AND a.entity_type = ''CUSTOM_REQUEST''
                    AND a.entity_id = CAST(r.id AS VARCHAR(60))),
                r.updated_at)
          FROM dbo.custom_order_request r
          WHERE r.quoted_amount IS NOT NULL AND r.quoted_at IS NULL');

    IF NOT EXISTS (SELECT 1 FROM sys.indexes
                   WHERE name = 'ix_custom_req_customer'
                     AND object_id = OBJECT_ID('dbo.custom_order_request'))
        CREATE INDEX ix_custom_req_customer
            ON dbo.custom_order_request (customer_id, created_at DESC);

    COMMIT TRANSACTION;
END TRY
BEGIN CATCH
    IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;
    THROW;
END CATCH;
GO
