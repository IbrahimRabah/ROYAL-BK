/* =====================================================================
   VELORA - V14: custom order requests

   A customer asks for a different size of a ready-made product, a product
   made to order, or fully custom work. A request is NOT a sale: it holds no
   stock and has no price until staff quote one and the customer accepts.

   - custom_order_request_sequence: one counter row per year. Same design as
     invoice_sequence: the number is allocated under a row lock in the same
     transaction that writes the request, so a rollback returns it. The
     customer is told the number over the phone, so gaps would be noticed.
     Requests are never deleted (REJECTED is a status), or the sequence would
     have a hole by definition.
   - request_number is unique forever; (fiscal_year, sequence_number) is unique,
     which is what actually guarantees there is no duplicate slot.
   - customer_id is NULL for a guest. phone is E.164.
   - converted_order_id is not set by anything yet: converting a request into an
     order is a separate feature. The column is ready for it.

   Safe to run again.
   ===================================================================== */

SET QUOTED_IDENTIFIER ON;
GO

IF OBJECT_ID('dbo.custom_order_request_sequence', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.custom_order_request_sequence (
        fiscal_year INT NOT NULL PRIMARY KEY,
        last_number INT NOT NULL DEFAULT 0,

        CONSTRAINT ck_custom_req_seq_positive CHECK (last_number >= 0)
    );
END
GO

IF OBJECT_ID('dbo.custom_order_request', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.custom_order_request (
        id                  BIGINT IDENTITY(1,1) PRIMARY KEY,
        request_number      VARCHAR(30)     NOT NULL,
        fiscal_year         INT             NOT NULL,
        sequence_number     INT             NOT NULL,

        type                VARCHAR(20)     NOT NULL,
        status              VARCHAR(20)     NOT NULL DEFAULT 'NEW',

        product_id          BIGINT          NULL,
        customer_id         BIGINT          NULL,

        contact_name        NVARCHAR(150)   NOT NULL,
        phone               VARCHAR(20)     NOT NULL,
        alt_phone           VARCHAR(20)     NULL,
        email               NVARCHAR(255)   NULL,

        governorate_id      BIGINT          NOT NULL,
        area                NVARCHAR(150)   NULL,
        street_address      NVARCHAR(255)   NULL,

        width_cm            DECIMAL(8,2)    NULL,
        height_cm           DECIMAL(8,2)    NULL,
        depth_cm            DECIMAL(8,2)    NULL,
        quantity            INT             NOT NULL DEFAULT 1,
        notes               NVARCHAR(1000)  NULL,

        quoted_amount       DECIMAL(19,4)   NULL,
        admin_note          NVARCHAR(1000)  NULL,
        converted_order_id  BIGINT          NULL,

        created_at          DATETIMEOFFSET  NOT NULL DEFAULT SYSDATETIMEOFFSET(),
        updated_at          DATETIMEOFFSET  NOT NULL DEFAULT SYSDATETIMEOFFSET(),

        CONSTRAINT fk_custom_req_product      FOREIGN KEY (product_id)         REFERENCES dbo.product(id),
        CONSTRAINT fk_custom_req_customer     FOREIGN KEY (customer_id)        REFERENCES dbo.app_user(id),
        CONSTRAINT fk_custom_req_governorate  FOREIGN KEY (governorate_id)     REFERENCES dbo.governorate(id),
        CONSTRAINT fk_custom_req_order        FOREIGN KEY (converted_order_id) REFERENCES dbo.customer_order(id),
        CONSTRAINT ck_custom_req_type   CHECK (type IN ('SIZE_VARIANT', 'MADE_TO_ORDER', 'CUSTOM_WORK')),
        CONSTRAINT ck_custom_req_status CHECK (status IN ('NEW', 'CONTACTED', 'QUOTED', 'ACCEPTED', 'REJECTED', 'CONVERTED')),
        CONSTRAINT ck_custom_req_quantity CHECK (quantity >= 1),
        CONSTRAINT ck_custom_req_quote CHECK (quoted_amount IS NULL OR quoted_amount > 0)
    );
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'uq_custom_req_number'
               AND object_id = OBJECT_ID('dbo.custom_order_request'))
BEGIN
    CREATE UNIQUE INDEX uq_custom_req_number ON dbo.custom_order_request (request_number);
END
GO

/* No two requests may share a slot in a year's sequence: the guarantee behind
   "no gaps, no duplicates". The application logic is only the mechanism. */
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'uq_custom_req_year_seq'
               AND object_id = OBJECT_ID('dbo.custom_order_request'))
BEGIN
    CREATE UNIQUE INDEX uq_custom_req_year_seq
        ON dbo.custom_order_request (fiscal_year, sequence_number);
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ix_custom_req_status'
               AND object_id = OBJECT_ID('dbo.custom_order_request'))
BEGIN
    CREATE INDEX ix_custom_req_status ON dbo.custom_order_request (status, created_at DESC);
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ix_custom_req_phone'
               AND object_id = OBJECT_ID('dbo.custom_order_request'))
BEGIN
    CREATE INDEX ix_custom_req_phone ON dbo.custom_order_request (phone);
END
GO

IF OBJECT_ID('dbo.custom_order_request_attachment', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.custom_order_request_attachment (
        id              BIGINT IDENTITY(1,1) PRIMARY KEY,
        request_id      BIGINT          NOT NULL,
        storage_key     NVARCHAR(500)   NOT NULL,
        content_type    VARCHAR(50)     NOT NULL,
        size_bytes      BIGINT          NOT NULL,
        created_at      DATETIMEOFFSET  NOT NULL DEFAULT SYSDATETIMEOFFSET(),

        CONSTRAINT fk_custom_req_att_request FOREIGN KEY (request_id)
            REFERENCES dbo.custom_order_request(id)
    );
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ix_custom_req_att_request'
               AND object_id = OBJECT_ID('dbo.custom_order_request_attachment'))
BEGIN
    CREATE INDEX ix_custom_req_att_request
        ON dbo.custom_order_request_attachment (request_id);
END
GO

SELECT name FROM sys.tables WHERE name LIKE 'custom_order_request%' ORDER BY name;
GO
