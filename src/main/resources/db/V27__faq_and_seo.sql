/* =====================================================================
   ROYAL - V27: FAQs, SEO for the static pages, and the SEO_EDITOR role

     faq_item     a question and its answer, in Arabic (required) and English (optional).
                  scope PRODUCT / CATEGORY: shown on that product or category page (target_id is
                  its id); scope GENERAL: shown on /faq (and on the checkout and contact pages by
                  topic). status DRAFT / PUBLISHED: only published items reach the public API.
     seo_page     the title, description and "hide from search" flag of the static pages that have
                  no product or category behind them (home, products list, about, contact,
                  portfolio, custom request, FAQ). One row per page_key, seeded here with empty
                  values so the SEO screen lists every page.
     role         SEO_EDITOR: may use the SEO and FAQ screens of the admin and nothing else.

   Product and category meta titles and descriptions already exist (product_translation,
   category_translation); nothing there changes.

   Re-runnable: every step checks whether it is already done. One transaction.
   Apply with:  sqlcmd -d <database> -f 65001 -b -i V27__faq_and_seo.sql
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

    IF OBJECT_ID('dbo.faq_item', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.faq_item (
            id             BIGINT IDENTITY(1,1) NOT NULL,
            scope          VARCHAR(20)     NOT NULL,
            target_id      BIGINT          NULL,
            topic          VARCHAR(30)     NULL,
            question_ar    NVARCHAR(500)   NOT NULL,
            answer_ar      NVARCHAR(2000)  NOT NULL,
            question_en    NVARCHAR(500)   NULL,
            answer_en      NVARCHAR(2000)  NULL,
            position       INT             NOT NULL CONSTRAINT df_faq_position DEFAULT 0,
            status         VARCHAR(20)     NOT NULL CONSTRAINT df_faq_status DEFAULT 'DRAFT',
            created_by     BIGINT          NULL,
            created_at     DATETIMEOFFSET  NOT NULL,
            updated_at     DATETIMEOFFSET  NOT NULL,
            CONSTRAINT pk_faq_item PRIMARY KEY (id),
            CONSTRAINT ck_faq_scope CHECK (scope IN ('PRODUCT', 'CATEGORY', 'GENERAL')),
            CONSTRAINT ck_faq_status CHECK (status IN ('DRAFT', 'PUBLISHED')),
            CONSTRAINT ck_faq_target CHECK ((scope = 'GENERAL' AND target_id IS NULL)
                                         OR (scope <> 'GENERAL' AND target_id IS NOT NULL))
        );
        CREATE INDEX ix_faq_lookup ON dbo.faq_item (scope, target_id, status, position);
    END;

    IF OBJECT_ID('dbo.seo_page', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.seo_page (
            id              BIGINT IDENTITY(1,1) NOT NULL,
            page_key        VARCHAR(40)     NOT NULL,
            title_ar        NVARCHAR(255)   NULL,
            description_ar  NVARCHAR(500)   NULL,
            title_en        NVARCHAR(255)   NULL,
            description_en  NVARCHAR(500)   NULL,
            noindex         BIT             NOT NULL CONSTRAINT df_seo_page_noindex DEFAULT 0,
            updated_by      BIGINT          NULL,
            created_at      DATETIMEOFFSET  NOT NULL,
            updated_at      DATETIMEOFFSET  NOT NULL,
            CONSTRAINT pk_seo_page PRIMARY KEY (id),
            CONSTRAINT uq_seo_page_key UNIQUE (page_key)
        );
    END;

    COMMIT TRANSACTION;
END TRY
BEGIN CATCH
    IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;
    THROW;
END CATCH;
GO

-- Seed rows go in a batch of their own: the tables must exist when it is compiled.
BEGIN TRY
    BEGIN TRANSACTION;

    DECLARE @keys TABLE (k VARCHAR(40));
    INSERT INTO @keys VALUES ('HOME'), ('PRODUCTS'), ('ABOUT'), ('CONTACT'), ('PORTFOLIO'),
                             ('CUSTOM_REQUEST'), ('FAQ');
    INSERT INTO dbo.seo_page (page_key, created_at, updated_at)
    SELECT k, SYSDATETIMEOFFSET(), SYSDATETIMEOFFSET()
    FROM @keys
    WHERE NOT EXISTS (SELECT 1 FROM dbo.seo_page p WHERE p.page_key = k);

    IF NOT EXISTS (SELECT 1 FROM dbo.role WHERE code = 'SEO_EDITOR')
        INSERT INTO dbo.role (code, name_ar, name_en, is_system)
        VALUES ('SEO_EDITOR', N'محرر SEO', N'SEO editor', 1);

    COMMIT TRANSACTION;
END TRY
BEGIN CATCH
    IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;
    THROW;
END CATCH;
GO
