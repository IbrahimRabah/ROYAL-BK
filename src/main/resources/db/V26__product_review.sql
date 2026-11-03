/* =====================================================================
   ROYAL - V26: product reviews, moderated by staff

   A customer who received a product can review it once. A review is invisible to the public
   until staff approve it.

     product_review     one review: who (customer_id, plus the name shown on the page, taken at
                        write time), which product, which delivered order line proves the purchase,
                        1-5 stars, an optional comment, and the moderation state
                        (PENDING / APPROVED / REJECTED, by whom, when, why).
                        UNIQUE (customer_id, product_id): one review per customer per product.
     The average and count shown on products are computed from the APPROVED rows (an @Formula on
     Product, like min_price), so they cannot drift and need no refresh step.

   Nothing existing is changed.
   Re-runnable: every step checks whether it is already done. One transaction per step.
   Apply with:  sqlcmd -d <database> -f 65001 -b -i V26__product_review.sql
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

    IF OBJECT_ID('dbo.product_review', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.product_review (
            id               BIGINT IDENTITY(1,1) NOT NULL,
            product_id       BIGINT          NOT NULL,
            order_item_id    BIGINT          NOT NULL,
            customer_id      BIGINT          NOT NULL,
            order_number     VARCHAR(30)     NOT NULL,
            reviewer_name    NVARCHAR(100)   NOT NULL,
            rating           SMALLINT        NOT NULL,
            comment          NVARCHAR(2000)  NULL,
            status           VARCHAR(20)     NOT NULL CONSTRAINT df_review_status DEFAULT 'PENDING',
            moderated_by     BIGINT          NULL,
            moderated_at     DATETIMEOFFSET  NULL,
            moderation_note  NVARCHAR(500)   NULL,
            created_at       DATETIMEOFFSET  NOT NULL,
            updated_at       DATETIMEOFFSET  NOT NULL,
            CONSTRAINT pk_product_review PRIMARY KEY (id),
            CONSTRAINT ck_review_rating CHECK (rating BETWEEN 1 AND 5),
            CONSTRAINT ck_review_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
            CONSTRAINT fk_review_product  FOREIGN KEY (product_id)    REFERENCES dbo.product (id),
            CONSTRAINT fk_review_item     FOREIGN KEY (order_item_id) REFERENCES dbo.order_item (id),
            CONSTRAINT fk_review_customer FOREIGN KEY (customer_id)   REFERENCES dbo.app_user (id),
            CONSTRAINT uq_review_customer_product UNIQUE (customer_id, product_id)
        );
        CREATE INDEX ix_review_status_created ON dbo.product_review (status, created_at DESC);
        CREATE INDEX ix_review_product_status ON dbo.product_review (product_id, status);
    END;

    COMMIT TRANSACTION;
END TRY
BEGIN CATCH
    IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;
    THROW;
END CATCH;
GO
