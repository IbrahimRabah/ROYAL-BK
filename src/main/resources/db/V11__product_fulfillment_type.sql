/* =====================================================================
   VELORA - V11: product.fulfillment_type and product.shipping_size_class

   fulfillment_type = HOW the product is sold (READY_MADE, MADE_TO_ORDER,
   CUSTOM_WORK). It is NOT the kind of product - watch / wallet / perfume
   is the category.

   shipping_size_class = SMALL / MEDIUM / LARGE. Required when
   fulfillment_type = READY_MADE, nullable otherwise.

   The READY_MADE size rule is enforced by ProductAdminService, not by a CHECK.

   Existing products become READY_MADE with shipping_size_class left NULL:
   no size is on record and a guessed one would be indistinguishable from a
   real one later. Staff must set a size on each before it can be edited
   (ProductAdminService requires one for READY_MADE). Safe to run again.
   ===================================================================== */

SET QUOTED_IDENTIFIER ON;
GO

IF NOT EXISTS (
    SELECT 1 FROM sys.columns
    WHERE object_id = OBJECT_ID('dbo.product') AND name = 'fulfillment_type'
)
BEGIN
    ALTER TABLE dbo.product ADD fulfillment_type VARCHAR(20) NOT NULL
        CONSTRAINT DF_product_fulfillment_type DEFAULT 'READY_MADE';
END
GO

IF NOT EXISTS (
    SELECT 1 FROM sys.columns
    WHERE object_id = OBJECT_ID('dbo.product') AND name = 'shipping_size_class'
)
BEGIN
    ALTER TABLE dbo.product ADD shipping_size_class VARCHAR(10) NULL;
END
GO


IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'CK_product_fulfillment_type')
BEGIN
    ALTER TABLE dbo.product ADD CONSTRAINT CK_product_fulfillment_type
        CHECK (fulfillment_type IN ('READY_MADE', 'MADE_TO_ORDER', 'CUSTOM_WORK'));
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'CK_product_shipping_size_class')
BEGIN
    ALTER TABLE dbo.product ADD CONSTRAINT CK_product_shipping_size_class
        CHECK (shipping_size_class IS NULL
               OR shipping_size_class IN ('SMALL', 'MEDIUM', 'LARGE'));
END
GO


SELECT fulfillment_type, shipping_size_class, COUNT(*) AS products
FROM dbo.product
GROUP BY fulfillment_type, shipping_size_class;
GO
