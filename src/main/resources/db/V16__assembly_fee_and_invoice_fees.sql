/* =====================================================================
   VELORA - V16: assembly (installation) fee, and the fees invoices were missing

   product
     requires_assembly   the piece has to be assembled / installed on delivery
     assembly_fee        what that costs, PER PIECE, tax-INCLUSIVE like every price
   customer_order
     assembly_total      sum of assembly_fee x quantity over the pieces that need assembly,
                         taken when the order is created, like every other amount
   order_item
     assembly_fee        the per-piece fee at purchase time (a snapshot, never read back
                         from the product): it is what lets an invoice, and later a partial
                         return, say where assembly_total came from
   invoice
     assembly_total      the order's assembly_total, as issued
     cod_fee             the order's cash-on-delivery fee, as issued

   invoice.cod_fee closes a gap, not a new feature: grand_total always included the COD fee,
   but the invoice had nowhere to show it, so with a fee above zero its rows would not have
   added up to the total. It is zero everywhere today. Any invoice whose order did carry a fee
   is backfilled from that order, so the figures on it finally add up.

   Everything defaults to zero / false: nothing is charged until a product is priced.

   Safe to run again. Apply with:  sqlcmd -d <database> -f 65001 -b -i V16__assembly_fee_and_invoice_fees.sql
   ===================================================================== */

SET QUOTED_IDENTIFIER ON;
GO

IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.product') AND name = 'requires_assembly')
BEGIN
    ALTER TABLE dbo.product ADD requires_assembly BIT NOT NULL
        CONSTRAINT df_product_requires_assembly DEFAULT 0;
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.product') AND name = 'assembly_fee')
BEGIN
    ALTER TABLE dbo.product ADD assembly_fee DECIMAL(19,4) NOT NULL
        CONSTRAINT df_product_assembly_fee DEFAULT 0;
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'ck_product_assembly_fee')
BEGIN
    ALTER TABLE dbo.product ADD CONSTRAINT ck_product_assembly_fee CHECK (assembly_fee >= 0);
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.customer_order') AND name = 'assembly_total')
BEGIN
    ALTER TABLE dbo.customer_order ADD assembly_total DECIMAL(19,4) NOT NULL
        CONSTRAINT df_order_assembly_total DEFAULT 0;
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'ck_order_assembly_total')
BEGIN
    ALTER TABLE dbo.customer_order ADD CONSTRAINT ck_order_assembly_total CHECK (assembly_total >= 0);
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.order_item') AND name = 'assembly_fee')
BEGIN
    ALTER TABLE dbo.order_item ADD assembly_fee DECIMAL(19,4) NOT NULL
        CONSTRAINT df_order_item_assembly_fee DEFAULT 0;
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'ck_order_item_assembly_fee')
BEGIN
    ALTER TABLE dbo.order_item ADD CONSTRAINT ck_order_item_assembly_fee CHECK (assembly_fee >= 0);
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.invoice') AND name = 'assembly_total')
BEGIN
    ALTER TABLE dbo.invoice ADD assembly_total DECIMAL(19,4) NOT NULL
        CONSTRAINT df_invoice_assembly_total DEFAULT 0;
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.invoice') AND name = 'cod_fee')
BEGIN
    ALTER TABLE dbo.invoice ADD cod_fee DECIMAL(19,4) NOT NULL
        CONSTRAINT df_invoice_cod_fee DEFAULT 0;
END
GO

/* An invoice for an order that carried a COD fee never showed it. Fill the missing figure from
   the order it was issued for. A no-op wherever no such order exists. */
UPDATE i
SET i.cod_fee = o.cod_fee
FROM dbo.invoice i
JOIN dbo.customer_order o ON o.id = i.order_id
WHERE i.cod_fee = 0 AND o.cod_fee > 0;
GO

/* ---------------------------------------------------------------------
   Verify
   --------------------------------------------------------------------- */

SELECT t.name AS tbl, c.name AS col, ty.name AS type_name, c.is_nullable
FROM sys.columns c
JOIN sys.tables t ON t.object_id = c.object_id
JOIN sys.types ty ON ty.user_type_id = c.user_type_id
WHERE (t.name = 'product' AND c.name IN ('requires_assembly', 'assembly_fee'))
   OR (t.name = 'customer_order' AND c.name = 'assembly_total')
   OR (t.name = 'order_item' AND c.name = 'assembly_fee')
   OR (t.name = 'invoice' AND c.name IN ('assembly_total', 'cod_fee'))
ORDER BY t.name, c.name;
GO
