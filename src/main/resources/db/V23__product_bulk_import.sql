/* =====================================================================
   ROYAL - V23: bulk product import (staging tables + specification attributes)

   Two things, both needed by the Excel bulk import:

   1. Staging. An uploaded sheet is parsed and validated, then kept here so staff can review
      every product before anything touches the catalogue:
        import_batch       one uploaded file (who, when, counts, PREVIEW / COMMITTED / DISCARDED)
        import_batch_row   one sheet row: the parsed values (JSON), the verdict, the messages, and
                           the product it became once the batch was committed
      Nothing in `product`, `product_variant` or `inventory` changes at upload time.

   2. The specification attributes the furniture sheet carries - dimensions, materials, colours,
      comfort, warranty. They are informational (TEXT / NUMBER / BOOLEAN, never variant-defining,
      never filterable), the same way MATERIAL, WARRANTY and LEAD_TIME already are. MATERIAL,
      WARRANTY and LEAD_TIME are reused: created here only on a database that lacks them (a fresh
      one), and left exactly as they are where they exist.
      Each new attribute gets an Arabic and an English name, and is linked to the bar-stool and
      chairs categories when those exist. An attribute whose code already exists is left alone.

   Re-runnable: every step checks whether it is already done. One transaction.
   Apply with:  sqlcmd -d <database> -f 65001 -b -i V23__product_bulk_import.sql
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

    /* 1. Staging tables ------------------------------------------------------------------ */
    IF OBJECT_ID('dbo.import_batch', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.import_batch (
            id             BIGINT IDENTITY(1,1) NOT NULL,
            file_name      NVARCHAR(255)   NOT NULL,
            status         VARCHAR(20)     NOT NULL,
            total_rows     INT             NOT NULL,
            create_rows    INT             NOT NULL,
            update_rows    INT             NOT NULL,
            error_rows     INT             NOT NULL,
            warning_rows   INT             NOT NULL,
            created_by     BIGINT          NULL,
            committed_at   DATETIMEOFFSET  NULL,
            created_at     DATETIMEOFFSET  NOT NULL,
            updated_at     DATETIMEOFFSET  NOT NULL,
            CONSTRAINT pk_import_batch PRIMARY KEY (id),
            CONSTRAINT ck_import_batch_status CHECK (status IN ('PREVIEW', 'COMMITTED', 'DISCARDED'))
        );
        CREATE INDEX ix_import_batch_created ON dbo.import_batch (created_at DESC);
    END;

    IF OBJECT_ID('dbo.import_batch_row', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.import_batch_row (
            id          BIGINT IDENTITY(1,1) NOT NULL,
            batch_id    BIGINT         NOT NULL,
            row_no      INT            NOT NULL,   -- the row number in the sheet, header = 1
            sku         VARCHAR(60)    NULL,
            action      VARCHAR(10)    NULL,       -- CREATE / UPDATE, null when the row is unreadable
            status      VARCHAR(10)    NOT NULL,   -- VALID / ERROR
            data        NVARCHAR(MAX)  NOT NULL,   -- the parsed row, JSON
            messages    NVARCHAR(MAX)  NULL,       -- [{column, severity, code, message}], JSON
            product_id  BIGINT         NULL,       -- set when the batch is committed
            CONSTRAINT pk_import_batch_row PRIMARY KEY (id),
            CONSTRAINT fk_import_row_batch FOREIGN KEY (batch_id)
                REFERENCES dbo.import_batch (id) ON DELETE CASCADE,
            CONSTRAINT uq_import_row UNIQUE (batch_id, row_no),
            CONSTRAINT ck_import_row_status CHECK (status IN ('VALID', 'ERROR')),
            CONSTRAINT ck_import_row_action CHECK (action IS NULL OR action IN ('CREATE', 'UPDATE'))
        );
        CREATE INDEX ix_import_row_status ON dbo.import_batch_row (batch_id, status, row_no);
    END;

    /* 2. Specification attributes -------------------------------------------------------- */
    DECLARE @attr TABLE (
        code VARCHAR(50), data_type VARCHAR(20), ord SMALLINT, name_ar NVARCHAR(300), name_en NVARCHAR(300)
    );

    INSERT INTO @attr (code, data_type, ord, name_ar, name_en) VALUES
        ('MATERIAL',            'TEXT',     1, N'الخامة الرئيسية',               N'Main material'),
        ('WARRANTY',            'TEXT',     2, N'الضمان',                        N'Warranty'),
        ('LEAD_TIME',           'TEXT',     3, N'مدة التجهيز',                   N'Lead time'),
        ('LEG_MATERIAL',        'TEXT',    10, N'خامة الرجل',                    N'Leg material'),
        ('UPHOLSTERY_MATERIAL', 'TEXT',    11, N'خامة التنجيد',                  N'Upholstery material'),
        ('UPHOLSTERY_DENSITY',  'NUMBER',  12, N'كثافة خامة التنجيد',            N'Upholstery density'),
        ('FABRIC_TYPE',         'TEXT',    13, N'نوع القماش',                    N'Fabric type'),
        ('BODY_COLOR',          'TEXT',    20, N'اللون الرئيسي',                 N'Main colour'),
        ('BODY_COLOR_DETAIL',   'TEXT',    21, N'اللون بالتفصيل',                N'Colour detail'),
        ('LEG_COLOR',           'TEXT',    22, N'لون الرجل',                     N'Leg colour'),
        ('LEG_COLOR_DETAIL',    'TEXT',    23, N'لون الرجل بالتفصيل',            N'Leg colour detail'),
        ('WIDTH_CM',            'NUMBER',  30, N'العرض (سم)',                    N'Width (cm)'),
        ('HEIGHT_CM',           'NUMBER',  31, N'الارتفاع (سم)',                 N'Height (cm)'),
        ('DEPTH_CM',            'NUMBER',  32, N'العمق (سم)',                    N'Depth (cm)'),
        ('SEAT_HEIGHT_CM',      'NUMBER',  33, N'ارتفاع القعدة (سم)',            N'Seat height (cm)'),
        ('SEAT_DEPTH_CM',       'NUMBER',  34, N'عمق القاعدة (سم)',              N'Seat depth (cm)'),
        ('ARMREST_HEIGHT_CM',   'NUMBER',  35, N'ارتفاع مسند الذراع (سم)',       N'Armrest height (cm)'),
        ('FLOOR_TO_ARM_CM',     'NUMBER',  36, N'الطول من الذراع للأرض (سم)',    N'Floor to arm (cm)'),
        ('PIECES_COUNT',        'NUMBER',  40, N'عدد القطع الرئيسية',            N'Main pieces'),
        ('COMFORT_LEVEL',       'NUMBER',  41, N'مستوى الراحة',                  N'Comfort level'),
        ('SWIVEL',              'BOOLEAN', 50, N'يتضمن وظيفة الدوران',           N'Swivel function'),
        ('MASSAGE',             'BOOLEAN', 51, N'يتضمن خاصية المساج',            N'Massage function'),
        ('ADJUSTABLE_FOOTREST', 'BOOLEAN', 52, N'مسند قدم قابل للتعديل',         N'Adjustable footrest'),
        ('ADJUSTABLE_HEADREST', 'BOOLEAN', 53, N'مسند رأس قابل للتعديل',         N'Adjustable headrest'),
        ('EXTENDABLE',          'BOOLEAN', 54, N'قابل للتمديد',                  N'Extendable'),
        ('EXTENSION_DIMENSIONS','TEXT',    55, N'أبعاد التمديد (سم)',            N'Extension dimensions (cm)'),
        ('HAS_MECHANISM',       'BOOLEAN', 56, N'يتضمن ميكانيزم',                N'Includes mechanism'),
        ('MECHANISM_COUNTRY',   'TEXT',    57, N'بلد الميكانيزم',                N'Mechanism origin'),
        ('MAX_USER_WEIGHT_KG',  'NUMBER',  60, N'أقصى وزن للمستخدم (كجم)',       N'Maximum user weight (kg)'),
        ('COUNTRY_OF_ORIGIN',   'TEXT',    61, N'بلد المنشأ',                    N'Country of origin');

    INSERT INTO dbo.attribute (code, data_type, is_variant_defining, is_filterable, display_order)
    SELECT a.code, a.data_type, 0, 0, a.ord
    FROM @attr a
    WHERE NOT EXISTS (SELECT 1 FROM dbo.attribute x WHERE x.code = a.code);

    INSERT INTO dbo.attribute_translation (attribute_id, locale, name)
    SELECT x.id, 'ar', a.name_ar
    FROM @attr a JOIN dbo.attribute x ON x.code = a.code
    WHERE NOT EXISTS (SELECT 1 FROM dbo.attribute_translation t
                      WHERE t.attribute_id = x.id AND t.locale = 'ar');

    INSERT INTO dbo.attribute_translation (attribute_id, locale, name)
    SELECT x.id, 'en', a.name_en
    FROM @attr a JOIN dbo.attribute x ON x.code = a.code
    WHERE NOT EXISTS (SELECT 1 FROM dbo.attribute_translation t
                      WHERE t.attribute_id = x.id AND t.locale = 'en');

    /* The attributes the sheet fills, plus the three that already existed, shown for the
       bar-stool and chairs categories. Missing categories are simply skipped. */
    INSERT INTO dbo.category_attribute (category_id, attribute_id, is_required, display_order)
    SELECT c.id, x.id, 0, x.display_order
    FROM dbo.category c
    JOIN dbo.attribute x ON x.code IN (SELECT code FROM @attr)
                         OR x.code IN ('MATERIAL', 'WARRANTY', 'LEAD_TIME')
    WHERE c.slug IN ('bar-stools', 'chairs')
      AND NOT EXISTS (SELECT 1 FROM dbo.category_attribute ca
                      WHERE ca.category_id = c.id AND ca.attribute_id = x.id);

    COMMIT TRANSACTION;
END TRY
BEGIN CATCH
    IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;
    THROW;
END CATCH;
GO
