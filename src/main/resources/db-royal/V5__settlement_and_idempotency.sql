/* =====================================================================
   VELORA â€” V5: COD settlement and idempotency

   Run once against the `velora` database.

   Both tables are new. `cod_remittance` may exist from V1 with a
   different shape; it is dropped only if empty, and the script refuses
   otherwise â€” a recorded settlement is a financial document.
   ===================================================================== */

GO

/* ---------------------------------------------------------------------
   1. Idempotency keys
   --------------------------------------------------------------------- */

DROP TABLE IF EXISTS idempotency_key;
GO

CREATE TABLE idempotency_key (
    id               BIGINT IDENTITY(1,1) PRIMARY KEY,
    idempotency_key  NVARCHAR(100)  NOT NULL,
    endpoint         NVARCHAR(200)  NOT NULL,
    request_hash     VARCHAR(64)    NULL,
    status           VARCHAR(20)    NOT NULL DEFAULT 'IN_PROGRESS',
    response_body    NVARCHAR(MAX)  NULL,
    response_status  INT            NULL,
    user_id          BIGINT         NULL,
    created_at       DATETIMEOFFSET NOT NULL DEFAULT SYSDATETIMEOFFSET(),
    completed_at     DATETIMEOFFSET NULL,
    expires_at       DATETIMEOFFSET NOT NULL,

    CONSTRAINT ck_idem_status CHECK (status IN ('IN_PROGRESS','COMPLETED','FAILED'))
);
GO

/* THE constraint that makes this work.

   The application claims a key by inserting a row and letting this index
   decide who wins. Checking whether the key exists and then inserting
   leaves a window that two simultaneous taps both pass through â€” the
   insert has to BE the test, not follow it. */
CREATE UNIQUE INDEX uq_idem_key_endpoint
    ON idempotency_key(idempotency_key, endpoint);

CREATE INDEX ix_idem_expires ON idempotency_key(expires_at);
GO

/* ---------------------------------------------------------------------
   2. COD settlement
   --------------------------------------------------------------------- */

IF OBJECT_ID('dbo.cod_remittance', 'U') IS NOT NULL
   AND EXISTS (SELECT 1 FROM cod_remittance)
BEGIN
    RAISERROR ('REFUSING TO RUN: cod_remittance already contains settlements. These are financial records and must not be dropped.', 16, 1);
    RETURN;
END
GO

DROP TABLE IF EXISTS cod_remittance_item;
DROP TABLE IF EXISTS cod_remittance;
GO

CREATE TABLE cod_remittance (
    id                 BIGINT IDENTITY(1,1) PRIMARY KEY,
    reference          NVARCHAR(40)   NOT NULL,
    courier_name       NVARCHAR(150)  NOT NULL,
    courier_reference  NVARCHAR(100)  NULL,
    settlement_date    DATE           NOT NULL,
    status             VARCHAR(20)    NOT NULL DEFAULT 'SETTLED',

    -- expected is the sum of the orders; received is what actually arrived.
    -- The gap between them is the reason this table exists.
    expected_amount    DECIMAL(19,4)  NOT NULL,
    received_amount    DECIMAL(19,4)  NOT NULL,
    difference         DECIMAL(19,4)  NOT NULL DEFAULT 0,

    order_count        INT            NOT NULL DEFAULT 0,
    note               NVARCHAR(1000) NULL,
    recorded_by        BIGINT         NULL,
    created_at         DATETIMEOFFSET NOT NULL DEFAULT SYSDATETIMEOFFSET(),
    cancelled_at       DATETIMEOFFSET NULL,

    CONSTRAINT ck_remittance_status CHECK (status IN ('SETTLED','SHORT','CANCELLED'))
);
GO

CREATE UNIQUE INDEX uq_remittance_reference ON cod_remittance(reference);
CREATE INDEX ix_remittance_date ON cod_remittance(settlement_date DESC);
GO

CREATE TABLE cod_remittance_item (
    id             BIGINT IDENTITY(1,1) PRIMARY KEY,
    remittance_id  BIGINT        NOT NULL,
    order_id       BIGINT        NOT NULL,

    -- Both snapshotted. A later refund changes the order total, and this
    -- batch must keep showing what was reconciled on the day.
    order_number   NVARCHAR(30)  NOT NULL,
    amount         DECIMAL(19,4) NOT NULL,

    CONSTRAINT fk_remittance_item_batch FOREIGN KEY (remittance_id)
        REFERENCES cod_remittance(id) ON DELETE CASCADE,
    CONSTRAINT fk_remittance_item_order FOREIGN KEY (order_id)
        REFERENCES customer_order(id)
);
GO

/* An order can appear in only one settlement. This is the guard against
   counting the same cash twice â€” the application checks the payment
   status first, but a unique index is what actually guarantees it. */
CREATE UNIQUE INDEX uq_remittance_item_order ON cod_remittance_item(order_id);
CREATE INDEX ix_remittance_item_batch ON cod_remittance_item(remittance_id);
GO

/* ---------------------------------------------------------------------
   3. Verify
   --------------------------------------------------------------------- */

SELECT name AS table_name
FROM sys.tables
WHERE name IN ('idempotency_key', 'cod_remittance', 'cod_remittance_item')
ORDER BY name;
GO

/* Should be zero on a clean install. Anything here is money the courier
   is holding. */
SELECT COUNT(*) AS unsettled_cod_orders,
       COALESCE(SUM(grand_total), 0) AS amount_with_courier
FROM customer_order
WHERE payment_method = 'COD'
  AND fulfillment_status = 'DELIVERED'
  AND payment_status = 'PENDING';
GO

PRINT 'Settlement and idempotency tables ready.';
GO

