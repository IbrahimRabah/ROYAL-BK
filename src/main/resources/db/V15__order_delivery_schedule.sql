/* =====================================================================
   VELORA - V15: delivery date preference, delivery appointment, AWAITING_SCHEDULE

   For furniture the delivery date is agreed BEFORE the goods leave the warehouse, because
   the date decides when they ship.

   customer_order gains
     preferred_delivery_date   the date the customer would like (they choose it at checkout)
     preferred_delivery_slot   MORNING | AFTERNOON | EVENING; only meaningful with a date
     scheduled_delivery_at     the appointment staff agreed (PATCH /admin/orders/{id}/schedule)
   All three are NULL on every existing order.

   The fulfilment status gains AWAITING_SCHEDULE, between CONFIRMED and PROCESSING. It is
   optional: staff may still go from CONFIRMED straight to PROCESSING. The status column is
   already VARCHAR(30), so the longest new value (17 characters) fits; what has to change is
   the CHECK constraint that lists the allowed values.

   Safe to run again. Apply with:  sqlcmd -d <database> -f 65001 -b -i V15__order_delivery_schedule.sql
   ===================================================================== */

SET QUOTED_IDENTIFIER ON;
GO

IF NOT EXISTS (SELECT 1 FROM sys.columns
               WHERE object_id = OBJECT_ID('dbo.customer_order') AND name = 'preferred_delivery_date')
BEGIN
    ALTER TABLE dbo.customer_order ADD preferred_delivery_date DATE NULL;
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.columns
               WHERE object_id = OBJECT_ID('dbo.customer_order') AND name = 'preferred_delivery_slot')
BEGIN
    ALTER TABLE dbo.customer_order ADD preferred_delivery_slot VARCHAR(10) NULL;
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.columns
               WHERE object_id = OBJECT_ID('dbo.customer_order') AND name = 'scheduled_delivery_at')
BEGIN
    ALTER TABLE dbo.customer_order ADD scheduled_delivery_at DATETIMEOFFSET(3) NULL;
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'ck_ord_delivery_slot')
BEGIN
    ALTER TABLE dbo.customer_order ADD CONSTRAINT ck_ord_delivery_slot
        CHECK (preferred_delivery_slot IS NULL
               OR preferred_delivery_slot IN ('MORNING', 'AFTERNOON', 'EVENING'));
END
GO

/* A slot is a time of day on a date: a slot with no date means nothing. */
IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'ck_ord_slot_needs_date')
BEGIN
    ALTER TABLE dbo.customer_order ADD CONSTRAINT ck_ord_slot_needs_date
        CHECK (preferred_delivery_slot IS NULL OR preferred_delivery_date IS NOT NULL);
END
GO

/* The status CHECK: replaced, not added to. Only when it does not yet know the new value. */
IF EXISTS (SELECT 1 FROM sys.check_constraints
           WHERE name = 'ck_ord_fulfillment'
             AND parent_object_id = OBJECT_ID('dbo.customer_order')
             AND definition NOT LIKE '%AWAITING_SCHEDULE%')
BEGIN
    ALTER TABLE dbo.customer_order DROP CONSTRAINT ck_ord_fulfillment;

    ALTER TABLE dbo.customer_order WITH CHECK ADD CONSTRAINT ck_ord_fulfillment
        CHECK (fulfillment_status IN (
            'PENDING', 'CONFIRMED', 'AWAITING_SCHEDULE', 'PROCESSING', 'SHIPPED',
            'OUT_FOR_DELIVERY', 'DELIVERED', 'DELIVERY_FAILED', 'REFUSED_ON_DELIVERY',
            'RETURNED_TO_SELLER', 'CANCELLED', 'RETURNED', 'PARTIALLY_RETURNED'));
END
GO

/* ---------------------------------------------------------------------
   Verify
   --------------------------------------------------------------------- */

SELECT c.name, ty.name AS type_name, c.is_nullable
FROM sys.columns c
JOIN sys.types ty ON ty.user_type_id = c.user_type_id
WHERE c.object_id = OBJECT_ID('dbo.customer_order')
  AND c.name IN ('preferred_delivery_date', 'preferred_delivery_slot', 'scheduled_delivery_at');

SELECT name, definition FROM sys.check_constraints
WHERE parent_object_id = OBJECT_ID('dbo.customer_order')
  AND name IN ('ck_ord_fulfillment', 'ck_ord_delivery_slot', 'ck_ord_slot_needs_date');
GO
