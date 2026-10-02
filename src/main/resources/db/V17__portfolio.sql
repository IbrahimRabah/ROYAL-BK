/* =====================================================================
   VELORA - V17: the portfolio (completed custom work shown on the storefront)

   A separate entity on purpose, not products of type CUSTOM_WORK: a product with no price and
   no stock inside the product table would distort catalogue reports, the dashboard and stock
   turnover.

   portfolio_item
     slug            the public URL (/portfolio/<slug>), unique over EVERY row including
                     archived ones: a link shared on WhatsApp must never start showing a
                     different piece
     title_ar / _en  Arabic is required, English optional
     category_id     optional, the same categories the catalogue uses
     published       shown on the storefront
     archived_at     soft delete: never removed, hidden from the storefront, restorable
   portfolio_image   images of an item; the file lives in storage, the row holds its key

   Safe to run again. Apply with:  sqlcmd -d <database> -f 65001 -b -i V17__portfolio.sql
   ===================================================================== */

SET QUOTED_IDENTIFIER ON;
GO

IF OBJECT_ID('dbo.portfolio_item', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.portfolio_item (
        id              BIGINT IDENTITY(1,1) NOT NULL,
        slug            VARCHAR(150)  COLLATE Arabic_CI_AS NOT NULL,
        title_ar        NVARCHAR(255) COLLATE Arabic_CI_AS NOT NULL,
        title_en        NVARCHAR(255) COLLATE Arabic_CI_AS NULL,
        description_ar  NVARCHAR(MAX) COLLATE Arabic_CI_AS NULL,
        description_en  NVARCHAR(MAX) COLLATE Arabic_CI_AS NULL,
        category_id     BIGINT NULL,
        completed_at    DATE NULL,
        display_order   INT NOT NULL CONSTRAINT df_portfolio_order DEFAULT (0),
        published       BIT NOT NULL CONSTRAINT df_portfolio_published DEFAULT (0),
        archived_at     DATETIMEOFFSET(3) NULL,
        created_at      DATETIMEOFFSET(3) NOT NULL,
        updated_at      DATETIMEOFFSET(3) NOT NULL,
        CONSTRAINT pk_portfolio_item PRIMARY KEY CLUSTERED (id),
        CONSTRAINT uq_portfolio_slug UNIQUE NONCLUSTERED (slug),
        CONSTRAINT fk_portfolio_category FOREIGN KEY (category_id) REFERENCES dbo.category (id),
        -- An archived item is never live.
        CONSTRAINT ck_portfolio_archived_unpublished CHECK (archived_at IS NULL OR published = 0)
    );
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ix_portfolio_listing'
               AND object_id = OBJECT_ID('dbo.portfolio_item'))
    CREATE NONCLUSTERED INDEX ix_portfolio_listing
        ON dbo.portfolio_item (published, archived_at, display_order);
GO

IF OBJECT_ID('dbo.portfolio_image', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.portfolio_image (
        id                 BIGINT IDENTITY(1,1) NOT NULL,
        portfolio_item_id  BIGINT NOT NULL,
        url                VARCHAR(500)  COLLATE Arabic_CI_AS NOT NULL,
        alt_text_ar        NVARCHAR(255) COLLATE Arabic_CI_AS NULL,
        alt_text_en        NVARCHAR(255) COLLATE Arabic_CI_AS NULL,
        is_main            BIT NOT NULL CONSTRAINT df_pimg_main DEFAULT (0),
        display_order      SMALLINT NOT NULL CONSTRAINT df_pimg_order DEFAULT (0),
        CONSTRAINT pk_portfolio_image PRIMARY KEY CLUSTERED (id),
        CONSTRAINT fk_pimg_item FOREIGN KEY (portfolio_item_id) REFERENCES dbo.portfolio_item (id)
    );
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ix_pimg_item'
               AND object_id = OBJECT_ID('dbo.portfolio_image'))
    CREATE NONCLUSTERED INDEX ix_pimg_item ON dbo.portfolio_image (portfolio_item_id);
GO

/* ---------------------------------------------------------------------
   Verify
   --------------------------------------------------------------------- */

SELECT t.name AS tbl, COUNT(*) AS columns_count
FROM sys.tables t
JOIN sys.columns c ON c.object_id = t.object_id
WHERE t.name IN ('portfolio_item', 'portfolio_image')
GROUP BY t.name;
GO
