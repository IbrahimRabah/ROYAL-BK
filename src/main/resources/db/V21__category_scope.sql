/* =====================================================================
   ROYAL - V21: a category is either for products or for the portfolio

   The portfolio reused the product category tree, so a category made for portfolio work (doors,
   windows, stairs) showed up in the shop navigation as an empty category.

   Adds category.scope - 'PRODUCT' or 'PORTFOLIO' - NOT NULL, DEFAULT 'PRODUCT'. Every existing
   row becomes PRODUCT through the default. Nothing else about a category changes.

   Two guarantees in the database itself, not only in the service code:
     uq_cat_id_scope        UNIQUE (id, scope): the target the composite key below needs.
     fk_cat_parent_scope    FOREIGN KEY (parent_id, scope) REFERENCES category (id, scope):
                            a child has the same scope as its parent. A top-level category
                            (parent_id NULL) passes, as with any nullable foreign key. Changing
                            the scope of a category that has children, or of a child alone, is
                            refused by the database.
   fk_cat_parent (parent_id -> id) stays as it was.

   uq_cat_slug is deliberately NOT changed: a slug stays unique across both scopes, so
   findBySlug is never ambiguous. A portfolio "doors" next to a shop "doors" takes "-2".

   Re-runnable: each step checks whether it is already done. One transaction, so a failure leaves
   the schema exactly as it was. The statements that name the new column run as dynamic SQL
   because the column does not exist yet when the batch is compiled.

   Apply with:  sqlcmd -d <database> -f 65001 -b -i V21__category_scope.sql
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

    /* 1. The column. Existing rows take the default, i.e. PRODUCT. */
    IF COL_LENGTH('dbo.category', 'scope') IS NULL
        EXEC('ALTER TABLE dbo.category
              ADD scope VARCHAR(20) NOT NULL CONSTRAINT df_cat_scope DEFAULT (''PRODUCT'')');

    IF NOT EXISTS (SELECT 1 FROM sys.check_constraints
                   WHERE name = 'ck_cat_scope' AND parent_object_id = OBJECT_ID('dbo.category'))
        EXEC('ALTER TABLE dbo.category
              ADD CONSTRAINT ck_cat_scope CHECK (scope IN (''PRODUCT'', ''PORTFOLIO''))');

    /* 2. Pre-flight: stop before adding the key if a child already differs from its parent. */
    DECLARE @mismatched INT;
    EXEC sp_executesql
        N'SELECT @n = COUNT(*) FROM dbo.category c
          JOIN dbo.category p ON p.id = c.parent_id
          WHERE c.scope <> p.scope',
        N'@n INT OUTPUT', @mismatched OUTPUT;
    IF @mismatched > 0
        THROW 52101, 'V21 aborted: a category has a different scope from its parent.', 1;

    /* 3. The key the composite foreign key points at. */
    IF NOT EXISTS (SELECT 1 FROM sys.key_constraints
                   WHERE name = 'uq_cat_id_scope' AND parent_object_id = OBJECT_ID('dbo.category'))
        EXEC('ALTER TABLE dbo.category ADD CONSTRAINT uq_cat_id_scope UNIQUE (id, scope)');

    /* 4. A child shares its parent's scope. */
    IF NOT EXISTS (SELECT 1 FROM sys.foreign_keys
                   WHERE name = 'fk_cat_parent_scope' AND parent_object_id = OBJECT_ID('dbo.category'))
        EXEC('ALTER TABLE dbo.category ADD CONSTRAINT fk_cat_parent_scope
              FOREIGN KEY (parent_id, scope) REFERENCES dbo.category (id, scope)');

    COMMIT TRANSACTION;
END TRY
BEGIN CATCH
    IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;
    THROW;
END CATCH;
GO
