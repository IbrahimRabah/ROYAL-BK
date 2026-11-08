/* =====================================================================
   ROYAL - V28: sale discounts with banners, and the announcement bar

     promotion      a percentage discount on ALL products, one CATEGORY (and its subcategories) or one
                    PRODUCT (every variant of it), between starts_at and ends_at. Only an ACTIVE
                    promotion inside its window changes a price. A banner image (and an optional mobile
                    one) is stored by key, like every other uploaded file.
     announcement   the message strip at the top of the storefront: text in Arabic and English, an
                    optional link, an animation and a colour theme, a schedule, and optionally a link to a
                    promotion (it then shows only while that promotion runs, and can count down to its end).
     order_item     list_price_gross, promotion_id and promotion_name: what the line would have cost
                    without the discount and which promotion gave it. unit_price_gross stays what the
                    customer paid, so tax, invoices and refunds work exactly as before. Existing rows
                    keep NULL.

   Re-runnable: every step checks whether it is already done. One transaction per step.
   Apply with:  sqlcmd -d <database> -f 65001 -b -i V28__promotions_and_announcements.sql
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

    IF OBJECT_ID('dbo.promotion', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.promotion (
            id                 BIGINT IDENTITY(1,1) NOT NULL,
            name_ar            NVARCHAR(150)   NOT NULL,
            name_en            NVARCHAR(150)   NULL,
            scope              VARCHAR(20)     NOT NULL,
            target_id          BIGINT          NULL,
            discount_percent   DECIMAL(5,2)    NOT NULL,
            starts_at          DATETIMEOFFSET  NOT NULL,
            ends_at            DATETIMEOFFSET  NOT NULL,
            status             VARCHAR(20)     NOT NULL CONSTRAINT df_promo_status DEFAULT 'DRAFT',
            headline_ar        NVARCHAR(200)   NULL,
            headline_en        NVARCHAR(200)   NULL,
            banner_key         VARCHAR(500)    NULL,
            banner_mobile_key  VARCHAR(500)    NULL,
            created_by         BIGINT          NULL,
            created_at         DATETIMEOFFSET  NOT NULL,
            updated_at         DATETIMEOFFSET  NOT NULL,
            CONSTRAINT pk_promotion PRIMARY KEY (id),
            CONSTRAINT ck_promo_scope CHECK (scope IN ('ALL', 'CATEGORY', 'PRODUCT')),
            CONSTRAINT ck_promo_status CHECK (status IN ('DRAFT', 'ACTIVE', 'PAUSED')),
            CONSTRAINT ck_promo_percent CHECK (discount_percent > 0 AND discount_percent <= 95),
            CONSTRAINT ck_promo_window CHECK (ends_at > starts_at),
            CONSTRAINT ck_promo_target CHECK ((scope = 'ALL' AND target_id IS NULL)
                                           OR (scope <> 'ALL' AND target_id IS NOT NULL))
        );
        CREATE INDEX ix_promo_running ON dbo.promotion (status, starts_at, ends_at);
    END;

    IF OBJECT_ID('dbo.announcement', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.announcement (
            id              BIGINT IDENTITY(1,1) NOT NULL,
            text_ar         NVARCHAR(300)   NULL,
            text_en         NVARCHAR(300)   NULL,
            link_url        VARCHAR(500)    NULL,
            link_label_ar   NVARCHAR(60)    NULL,
            link_label_en   NVARCHAR(60)    NULL,
            animation       VARCHAR(20)     NOT NULL CONSTRAINT df_ann_animation DEFAULT 'MARQUEE',
            theme           VARCHAR(20)     NOT NULL CONSTRAINT df_ann_theme DEFAULT 'GOLD',
            starts_at       DATETIMEOFFSET  NULL,
            ends_at         DATETIMEOFFSET  NULL,
            active          BIT             NOT NULL CONSTRAINT df_ann_active DEFAULT 1,
            position        INT             NOT NULL CONSTRAINT df_ann_position DEFAULT 0,
            dismissible     BIT             NOT NULL CONSTRAINT df_ann_dismissible DEFAULT 1,
            show_countdown  BIT             NOT NULL CONSTRAINT df_ann_countdown DEFAULT 0,
            promotion_id    BIGINT          NULL,
            created_by      BIGINT          NULL,
            created_at      DATETIMEOFFSET  NOT NULL,
            updated_at      DATETIMEOFFSET  NOT NULL,
            CONSTRAINT pk_announcement PRIMARY KEY (id),
            CONSTRAINT ck_ann_animation CHECK (animation IN ('STATIC', 'MARQUEE', 'FADE', 'PULSE')),
            CONSTRAINT ck_ann_theme CHECK (theme IN ('GOLD', 'DARK', 'RED', 'GREEN', 'LIGHT')),
            CONSTRAINT ck_ann_text CHECK (promotion_id IS NOT NULL
                                       OR (text_ar IS NOT NULL AND LTRIM(RTRIM(text_ar)) <> N'')),
            CONSTRAINT fk_ann_promotion FOREIGN KEY (promotion_id) REFERENCES dbo.promotion (id)
        );
        CREATE INDEX ix_ann_active ON dbo.announcement (active, position);
    END;

    IF COL_LENGTH('dbo.order_item', 'list_price_gross') IS NULL
        ALTER TABLE dbo.order_item ADD list_price_gross DECIMAL(19,4) NULL;
    IF COL_LENGTH('dbo.order_item', 'promotion_id') IS NULL
        ALTER TABLE dbo.order_item ADD promotion_id BIGINT NULL;
    IF COL_LENGTH('dbo.order_item', 'promotion_name') IS NULL
        ALTER TABLE dbo.order_item ADD promotion_name NVARCHAR(200) NULL;

    COMMIT TRANSACTION;
END TRY
BEGIN CATCH
    IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;
    THROW;
END CATCH;
GO

-- The discount a product gets right now, for price filters and sorting (which must be SQL). The prices that are
-- DISPLAYED come from PromotionPricingService; a test checks the two agree to the piastre. Product, category,
-- and up to two ancestors of the category, like the Java side. Own batch: CREATE FUNCTION must be first.
CREATE OR ALTER FUNCTION dbo.fn_best_discount_percent(@product_id BIGINT)
RETURNS DECIMAL(5,2)
AS
BEGIN
    DECLARE @cat BIGINT = (SELECT category_id FROM dbo.product WHERE id = @product_id);
    DECLARE @parent BIGINT = (SELECT parent_id FROM dbo.category WHERE id = @cat);
    DECLARE @grand BIGINT = (SELECT parent_id FROM dbo.category WHERE id = @parent);
    RETURN (SELECT COALESCE(MAX(pr.discount_percent), 0)
            FROM dbo.promotion pr
            WHERE pr.status = 'ACTIVE'
              AND pr.starts_at <= SYSDATETIMEOFFSET() AND pr.ends_at > SYSDATETIMEOFFSET()
              AND (pr.scope = 'ALL'
                   OR (pr.scope = 'PRODUCT' AND pr.target_id = @product_id)
                   OR (pr.scope = 'CATEGORY' AND pr.target_id IN (@cat, @parent, @grand))));
END;
GO

-- Any login the application uses (the restricted test login too) may call it: it reads discount rows only.
GRANT EXECUTE ON OBJECT::dbo.fn_best_discount_percent TO PUBLIC;
GO
