/* =====================================================================
   ROYAL - reset-demo-transactions.sql

   *** PRE-PRODUCTION DEMO DATA ONLY. ***
   *** THIS SCRIPT DELETES ORDERS, INVOICES AND CUSTOM REQUESTS. ***
   *** TAKE A BACKUP OF `royal` BEFORE THE FIRST RUN. ***
     e.g.  BACKUP DATABASE royal TO DISK = N'D:\backups\royal_before_reset.bak' WITH INIT;

   Once the store is live this script must NEVER be run: deleting issued invoices is
   forbidden (invoices are immutable; corrections are credit notes).

   WHAT IT DOES
     Deletes, in foreign-key order: remittances, credit notes, invoices, notification log,
     returns, refunds, payment transactions, shipments, order status history, stock
     reservations, order items, custom requests and their attachments, orders, carts,
     idempotency keys, OTP codes, the audit rows about all of the above, and the order-driven
     stock movements. Resets invoice_sequence and custom_order_request_sequence to 0.

   WHAT IT KEEPS
     Products, variants, categories, brands, attributes, portfolio items and images,
     governorates, shipping zones and rates, store_profile, users, roles, and the
     non-order stock history (PURCHASE_RECEIVED, MANUAL_ADJUSTMENT, DAMAGE_WRITEOFF - the
     ledger that explains how the opening stock got there). Product-level audit rows
     (price, publish, stock receipts, shipping rates, permissions, portfolio) are kept too.

   STOCK
     qty_on_hand is a STORED column, so deleting movements alone would leave it wrong. The
     script first subtracts, per variant, the net quantity_delta of the order-driven movements
     it is about to delete (SALE, CANCELLATION_RESTOCK, RETURN_SELLABLE, RETURN_DAMAGED), then
     deletes them, and sets qty_reserved to 0 (every reservation is deleted). It aborts if any
     quantity would end up negative. Manual stock adjustments made DURING testing are kept as
     real history - correct them from the admin panel.

   *** STOP THE APPLICATION BEFORE RUNNING. It is NOT safe with the app up: ***
     - the invoice and custom-request counters are allocated under a row lock in the issuing
       transaction; resetting them while a request is in flight can hand out a number that
       already exists (or collide with the unique index);
     - the in-memory rate limiter (custom-request form, login, OTP) lives in the app process
       and is cleared only by RESTARTING it, not by SQL;
     - the app reads inventory with optimistic locking and would write stale quantities.
     Stop the app, run this, start the app.

   FILES ARE NOT DELETED. Uploaded custom-request attachments and invoice PDFs stay on disk;
   the script prints those two folders at the end so you can clean them by hand.

   SAFETY
     - Refuses to run unless the connected database is exactly `royal`. Choose the database
       with -d; this file has no USE statement and must never get one.
     - Everything after the one GO (which only sets QUOTED_IDENTIFIER) is a single batch, so
       the guard aborts everything below it.
     - One transaction; any error rolls everything back.

   Run:  sqlcmd -S localhost,1433 -U sa -d royal -f 65001 -b -i src\main\resources\db-royal\reset-demo-transactions.sql
   ===================================================================== */

/* Needed before the main batch compiles: some tables have filtered indexes, and sqlcmd starts
   with QUOTED_IDENTIFIER OFF. This is the ONLY GO in the file; everything after it, guard
   included, is one batch, so the guard's THROW stops all of it. */
SET QUOTED_IDENTIFIER ON;
GO

SET NOCOUNT ON;
SET XACT_ABORT ON;

DECLARE @guard NVARCHAR(400) = N'REFUSING TO RUN: connected database is [' + ISNULL(DB_NAME(), N'?')
    + N'], not [royal]. This script only ever runs against royal. Nothing was changed.';
IF DB_NAME() <> N'royal' THROW 50001, @guard, 1;

CREATE TABLE #counts (tbl SYSNAME NOT NULL PRIMARY KEY, before_n INT NOT NULL, after_n INT NULL);

INSERT INTO #counts (tbl, before_n)
SELECT N'cod_remittance_item', COUNT(*) FROM dbo.cod_remittance_item
UNION ALL SELECT N'cod_remittance', COUNT(*) FROM dbo.cod_remittance
UNION ALL SELECT N'credit_note', COUNT(*) FROM dbo.credit_note
UNION ALL SELECT N'invoice', COUNT(*) FROM dbo.invoice
UNION ALL SELECT N'notification_log', COUNT(*) FROM dbo.notification_log
UNION ALL SELECT N'return_item', COUNT(*) FROM dbo.return_item
UNION ALL SELECT N'return_request', COUNT(*) FROM dbo.return_request
UNION ALL SELECT N'refund', COUNT(*) FROM dbo.refund
UNION ALL SELECT N'payment_transaction', COUNT(*) FROM dbo.payment_transaction
UNION ALL SELECT N'shipment', COUNT(*) FROM dbo.shipment
UNION ALL SELECT N'order_status_history', COUNT(*) FROM dbo.order_status_history
UNION ALL SELECT N'stock_reservation', COUNT(*) FROM dbo.stock_reservation
UNION ALL SELECT N'order_item', COUNT(*) FROM dbo.order_item
UNION ALL SELECT N'custom_order_request_attachment', COUNT(*) FROM dbo.custom_order_request_attachment
UNION ALL SELECT N'custom_order_request', COUNT(*) FROM dbo.custom_order_request
UNION ALL SELECT N'customer_order', COUNT(*) FROM dbo.customer_order
UNION ALL SELECT N'cart_item', COUNT(*) FROM dbo.cart_item
UNION ALL SELECT N'cart', COUNT(*) FROM dbo.cart
UNION ALL SELECT N'stock_movement', COUNT(*) FROM dbo.stock_movement
UNION ALL SELECT N'idempotency_key', COUNT(*) FROM dbo.idempotency_key
UNION ALL SELECT N'otp_verification', COUNT(*) FROM dbo.otp_verification
UNION ALL SELECT N'audit_log', COUNT(*) FROM dbo.audit_log
UNION ALL SELECT N'invoice_sequence (sum last_number)', COALESCE(SUM(last_number), 0) FROM dbo.invoice_sequence
UNION ALL SELECT N'custom_order_request_sequence (sum last_number)', COALESCE(SUM(last_number), 0) FROM dbo.custom_order_request_sequence
UNION ALL SELECT N'inventory (sum qty_on_hand)', COALESCE(SUM(qty_on_hand), 0) FROM dbo.inventory
UNION ALL SELECT N'inventory (sum qty_reserved)', COALESCE(SUM(qty_reserved), 0) FROM dbo.inventory;

BEGIN TRY
BEGIN TRANSACTION;

    /* 1. Stock first: it needs the movements that step 4 deletes. */
    UPDATE i
       SET i.qty_on_hand = i.qty_on_hand - m.net,
           i.version = i.version + 1,
           i.updated_at = SYSDATETIMEOFFSET()
      FROM dbo.inventory i
      JOIN (SELECT variant_id, SUM(quantity_delta) AS net
              FROM dbo.stock_movement
             WHERE movement_type IN ('SALE', 'CANCELLATION_RESTOCK', 'RETURN_SELLABLE', 'RETURN_DAMAGED')
             GROUP BY variant_id) m ON m.variant_id = i.variant_id;

    UPDATE dbo.inventory SET qty_reserved = 0, updated_at = SYSDATETIMEOFFSET() WHERE qty_reserved <> 0;

    IF EXISTS (SELECT 1 FROM dbo.inventory WHERE qty_on_hand < 0)
        THROW 50002, N'Restoring stock would make a quantity negative. Rolled back; investigate stock_movement first.', 1;

    /* 2. Children before parents. */
    DELETE FROM dbo.cod_remittance_item;
    DELETE FROM dbo.cod_remittance;
    DELETE FROM dbo.credit_note;
    DELETE FROM dbo.invoice;
    DELETE FROM dbo.notification_log;
    DELETE FROM dbo.return_item;
    DELETE FROM dbo.return_request;
    DELETE FROM dbo.refund;
    DELETE FROM dbo.payment_transaction;
    DELETE FROM dbo.shipment;
    DELETE FROM dbo.order_status_history;
    DELETE FROM dbo.stock_reservation;
    DELETE FROM dbo.order_item;
    DELETE FROM dbo.custom_order_request_attachment;
    DELETE FROM dbo.custom_order_request;
    DELETE FROM dbo.customer_order;

    /* 3. Carts: all of them. Pre-production, every cart is test activity. */
    DELETE FROM dbo.cart_item;
    DELETE FROM dbo.cart;

    /* 4. Order-driven stock movements (quantities were corrected in step 1). */
    DELETE FROM dbo.stock_movement
     WHERE movement_type IN ('SALE', 'CANCELLATION_RESTOCK', 'RETURN_SELLABLE', 'RETURN_DAMAGED');

    /* 5. Idempotency keys (a stale key would replay an order that no longer exists) and OTP
          rows (their per-phone counts drive the OTP rate limit). */
    DELETE FROM dbo.idempotency_key;
    DELETE FROM dbo.otp_verification;

    /* 6. Audit rows about what was deleted. Product, stock, shipping, portfolio and
          permission audit rows stay. */
    DELETE FROM dbo.audit_log
     WHERE entity_type IN ('ORDER', 'INVOICE', 'COD_REMITTANCE', 'CUSTOM_REQUEST')
        OR action IN ('CUSTOM_REQUEST_STATUS_CHANGED', 'CUSTOM_REQUEST_QUOTED',
                      'ORDER_DELIVERY_SCHEDULED', 'INVOICE_CANCELLED', 'PAYMENT_ADJUSTED');

    /* 7. Counters back to zero. */
    UPDATE dbo.invoice_sequence SET last_number = 0;
    UPDATE dbo.custom_order_request_sequence SET last_number = 0;

    /* 8. After counts. */
    UPDATE #counts SET after_n = (SELECT COUNT(*) FROM dbo.cod_remittance_item) WHERE tbl = N'cod_remittance_item';
    UPDATE #counts SET after_n = (SELECT COUNT(*) FROM dbo.cod_remittance) WHERE tbl = N'cod_remittance';
    UPDATE #counts SET after_n = (SELECT COUNT(*) FROM dbo.credit_note) WHERE tbl = N'credit_note';
    UPDATE #counts SET after_n = (SELECT COUNT(*) FROM dbo.invoice) WHERE tbl = N'invoice';
    UPDATE #counts SET after_n = (SELECT COUNT(*) FROM dbo.notification_log) WHERE tbl = N'notification_log';
    UPDATE #counts SET after_n = (SELECT COUNT(*) FROM dbo.return_item) WHERE tbl = N'return_item';
    UPDATE #counts SET after_n = (SELECT COUNT(*) FROM dbo.return_request) WHERE tbl = N'return_request';
    UPDATE #counts SET after_n = (SELECT COUNT(*) FROM dbo.refund) WHERE tbl = N'refund';
    UPDATE #counts SET after_n = (SELECT COUNT(*) FROM dbo.payment_transaction) WHERE tbl = N'payment_transaction';
    UPDATE #counts SET after_n = (SELECT COUNT(*) FROM dbo.shipment) WHERE tbl = N'shipment';
    UPDATE #counts SET after_n = (SELECT COUNT(*) FROM dbo.order_status_history) WHERE tbl = N'order_status_history';
    UPDATE #counts SET after_n = (SELECT COUNT(*) FROM dbo.stock_reservation) WHERE tbl = N'stock_reservation';
    UPDATE #counts SET after_n = (SELECT COUNT(*) FROM dbo.order_item) WHERE tbl = N'order_item';
    UPDATE #counts SET after_n = (SELECT COUNT(*) FROM dbo.custom_order_request_attachment) WHERE tbl = N'custom_order_request_attachment';
    UPDATE #counts SET after_n = (SELECT COUNT(*) FROM dbo.custom_order_request) WHERE tbl = N'custom_order_request';
    UPDATE #counts SET after_n = (SELECT COUNT(*) FROM dbo.customer_order) WHERE tbl = N'customer_order';
    UPDATE #counts SET after_n = (SELECT COUNT(*) FROM dbo.cart_item) WHERE tbl = N'cart_item';
    UPDATE #counts SET after_n = (SELECT COUNT(*) FROM dbo.cart) WHERE tbl = N'cart';
    UPDATE #counts SET after_n = (SELECT COUNT(*) FROM dbo.stock_movement) WHERE tbl = N'stock_movement';
    UPDATE #counts SET after_n = (SELECT COUNT(*) FROM dbo.idempotency_key) WHERE tbl = N'idempotency_key';
    UPDATE #counts SET after_n = (SELECT COUNT(*) FROM dbo.otp_verification) WHERE tbl = N'otp_verification';
    UPDATE #counts SET after_n = (SELECT COUNT(*) FROM dbo.audit_log) WHERE tbl = N'audit_log';
    UPDATE #counts SET after_n = (SELECT COALESCE(SUM(last_number), 0) FROM dbo.invoice_sequence) WHERE tbl = N'invoice_sequence (sum last_number)';
    UPDATE #counts SET after_n = (SELECT COALESCE(SUM(last_number), 0) FROM dbo.custom_order_request_sequence) WHERE tbl = N'custom_order_request_sequence (sum last_number)';
    UPDATE #counts SET after_n = (SELECT COALESCE(SUM(qty_on_hand), 0) FROM dbo.inventory) WHERE tbl = N'inventory (sum qty_on_hand)';
    UPDATE #counts SET after_n = (SELECT COALESCE(SUM(qty_reserved), 0) FROM dbo.inventory) WHERE tbl = N'inventory (sum qty_reserved)';

COMMIT TRANSACTION;
END TRY
BEGIN CATCH
    IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;
    PRINT N'RESET FAILED - everything was rolled back.';
    THROW;
END CATCH;

SELECT tbl AS [table], before_n AS [before], after_n AS [after] FROM #counts ORDER BY tbl;

SELECT v.sku, i.qty_on_hand, i.qty_reserved
  FROM dbo.inventory i JOIN dbo.product_variant v ON v.id = i.variant_id
 ORDER BY v.sku;

PRINT N'';
PRINT N'DONE. Now clean up by hand - SQL cannot delete files. Inside the storage root';
PRINT N'(storage.local-path in application.yml), delete the CONTENTS of exactly these two folders:';
PRINT N'  custom-requests/   - attachments of the custom requests just deleted';
PRINT N'  invoices/          - PDFs of the invoices just deleted';
PRINT N'Do not touch any other folder in the storage root.';
PRINT N'Then restart the application (this clears the in-memory rate limiter).';
