/* =====================================================================
   ROYAL - V20: the company is not registered for VAT, so the rate is zero

   Sets product_variant.tax_rate to 0 on every variant, archived ones included, and makes 0 the
   column's default (df_var_tax was 0.1400).

   NOT removed: the tax_rate column, its use in checkout, the invoice tax columns. If the company
   registers for VAT, put the rate back (default 0.1400 here and in ProductVariant.DEFAULT_TAX_RATE,
   then the variants' own rates in the admin) and everything works as before.

   NOT touched, on purpose: customer_order, order_item, invoice and every stored tax total. An
   order or invoice created at 14% keeps its tax for good; the order line carries its own rate.
   Only product_variant and its default are written.

   Safe to run again: the UPDATE only matches variants that still carry a rate, and the default is
   dropped and re-created. Everything runs in one transaction, so a failure leaves the schema and
   the data exactly as they were.

   Apply with:  sqlcmd -d <database> -f 65001 -b -i V20__zero_tax_rate.sql
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

    IF EXISTS (SELECT 1 FROM sys.default_constraints
               WHERE name = 'df_var_tax' AND parent_object_id = OBJECT_ID('dbo.product_variant'))
        ALTER TABLE dbo.product_variant DROP CONSTRAINT df_var_tax;

    ALTER TABLE dbo.product_variant
        ADD CONSTRAINT df_var_tax DEFAULT (0.0000) FOR tax_rate;

    UPDATE dbo.product_variant SET tax_rate = 0.0000 WHERE tax_rate <> 0;

    COMMIT TRANSACTION;
END TRY
BEGIN CATCH
    IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;
    THROW;
END CATCH;
GO
