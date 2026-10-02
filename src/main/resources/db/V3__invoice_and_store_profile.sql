/* =====================================================================
   VELORA — V3: store profile and invoices

   Run once against the database you are upgrading (sqlcmd -d <database>).

   The invoice tables are DROPPED and recreated. That is safe here and
   only here: no invoice has ever been issued, so there is nothing to
   lose. Never run this part again once invoices exist — they are legal
   records and the numbering must never restart.
   ===================================================================== */

/* ---------------------------------------------------------------------
   1. Seller identity — one row, id = 1.

   A dedicated table rather than key/value rows in store_setting: this is
   a fixed, known set of fields, so typed columns and constraints beat a
   bag of strings.
   --------------------------------------------------------------------- */

IF OBJECT_ID('dbo.store_profile', 'U') IS NULL
BEGIN
    CREATE TABLE store_profile (
        id                  INT             NOT NULL PRIMARY KEY,
        legal_name          NVARCHAR(200)   NOT NULL,
        legal_name_en       NVARCHAR(200)   NULL,
        [address]           NVARCHAR(500)   NULL,
        phone               NVARCHAR(30)    NULL,
        email               NVARCHAR(255)   NULL,
        tax_number          NVARCHAR(30)    NULL,
        commercial_register NVARCHAR(30)    NULL,
        website             NVARCHAR(255)   NULL,
        invoice_footer_note NVARCHAR(500)   NULL,
        updated_at          DATETIMEOFFSET  NOT NULL DEFAULT SYSDATETIMEOFFSET(),

        CONSTRAINT ck_store_profile_single_row CHECK (id = 1)
    );
END
GO

/* Seed the row. Edit through the API, not here:
   PUT /api/v1/admin/settings/store-profile

   tax_number is deliberately NULL. A blank field is omitted from the
   invoice entirely, and a wrong tax number on a legal document is far
   worse than none at all. */
IF NOT EXISTS (SELECT 1 FROM store_profile WHERE id = 1)
BEGIN
    INSERT INTO store_profile
        (id, legal_name, legal_name_en, [address], phone, email,
         tax_number, commercial_register, invoice_footer_note)
    VALUES
        (1,
         N'VELORA',
         N'VELORA',
         N'القاهرة، مصر',
         N'01090386165',
         N'ibrahimrabah25@gmail.com',
         NULL,
         NULL,
         N'شكراً لتسوقكم من فيلورا');
END
GO

/* ---------------------------------------------------------------------
   2. Invoice tables.

   Dropped first because no invoice has been issued yet. If that ever
   stops being true, this section must not run again.
   --------------------------------------------------------------------- */

IF EXISTS (SELECT 1 FROM sys.tables WHERE name = 'invoice')
   AND EXISTS (SELECT 1 FROM invoice)
BEGIN
    RAISERROR ('REFUSING TO RUN: invoices already exist. Dropping them would restart the numbering sequence, and invoice numbers must never be reused.', 16, 1);
    RETURN;
END
GO

IF OBJECT_ID('dbo.invoice', 'U') IS NOT NULL DROP TABLE invoice;
IF OBJECT_ID('dbo.invoice_sequence', 'U') IS NOT NULL DROP TABLE invoice_sequence;
GO

/* One counter per fiscal year.

   NOT an identity column, deliberately. An identity leaves gaps whenever
   a transaction rolls back — fine for an order id, unacceptable for an
   invoice number. The application allocates from here under a row lock
   inside the same transaction that writes the invoice, so a rollback
   returns the number instead of burning it. */
CREATE TABLE invoice_sequence (
    fiscal_year INT NOT NULL PRIMARY KEY,
    last_number INT NOT NULL DEFAULT 0,

    CONSTRAINT ck_invoice_seq_positive CHECK (last_number >= 0)
);
GO

CREATE TABLE invoice (
    id                          BIGINT IDENTITY(1,1) PRIMARY KEY,
    invoice_number              NVARCHAR(40)    NOT NULL,
    fiscal_year                 INT             NOT NULL,
    sequence_number             INT             NOT NULL,
    order_id                    BIGINT          NOT NULL,
    status                      VARCHAR(20)     NOT NULL DEFAULT 'ISSUED',

    -- seller snapshot: the store's details WILL change, and an invoice
    -- must always show what was printed on it
    seller_name                 NVARCHAR(200)   NOT NULL,
    seller_address              NVARCHAR(500)   NULL,
    seller_phone                NVARCHAR(30)    NULL,
    seller_email                NVARCHAR(255)   NULL,
    seller_tax_number           NVARCHAR(30)    NULL,
    seller_commercial_register  NVARCHAR(30)    NULL,

    -- buyer snapshot
    buyer_name                  NVARCHAR(150)   NOT NULL,
    buyer_phone                 NVARCHAR(20)    NOT NULL,
    buyer_address               NVARCHAR(800)   NULL,

    -- money snapshot, all tax-INCLUSIVE except net_total
    currency                    CHAR(3)         NOT NULL DEFAULT 'EGP',
    subtotal_gross              DECIMAL(19,4)   NOT NULL,
    discount_total              DECIMAL(19,4)   NOT NULL DEFAULT 0,
    shipping_cost               DECIMAL(19,4)   NOT NULL DEFAULT 0,
    grand_total                 DECIMAL(19,4)   NOT NULL,
    tax_total                   DECIMAL(19,4)   NOT NULL,
    net_total                   DECIMAL(19,4)   NOT NULL,
    payment_method              VARCHAR(20)     NOT NULL,

    pdf_key                     NVARCHAR(500)   NULL,
    issued_at                   DATETIMEOFFSET  NOT NULL DEFAULT SYSDATETIMEOFFSET(),
    cancelled_at                DATETIMEOFFSET  NULL,
    cancel_reason               NVARCHAR(255)   NULL,

    CONSTRAINT fk_invoice_order FOREIGN KEY (order_id) REFERENCES customer_order(id),
    CONSTRAINT ck_invoice_status CHECK (status IN ('ISSUED', 'CANCELLED'))
);
GO

/* The number is unique forever, across years. */
CREATE UNIQUE INDEX uq_invoice_number ON invoice(invoice_number);

/* No two invoices may share a slot in a year's sequence. This is the
   constraint that actually enforces gaplessness — the application logic
   is the mechanism, this is the guarantee. */
CREATE UNIQUE INDEX uq_invoice_year_seq ON invoice(fiscal_year, sequence_number);

/* One invoice per order. */
CREATE UNIQUE INDEX uq_invoice_order ON invoice(order_id);

CREATE INDEX ix_invoice_issued ON invoice(issued_at DESC);
GO

/* ---------------------------------------------------------------------
   3. Verify
   --------------------------------------------------------------------- */

SELECT legal_name AS الاسم,
       [address]  AS العنوان,
       phone      AS الهاتف,
       ISNULL(tax_number, N'(غير مسجل بعد)') AS الرقم_الضريبي
FROM store_profile;
GO

SELECT COUNT(*) AS invoice_tables_ready
FROM sys.tables
WHERE name IN ('invoice', 'invoice_sequence');
GO

PRINT 'Store profile seeded and invoice tables created. Set the tax number via PUT /api/v1/admin/settings/store-profile once registered.';
GO
