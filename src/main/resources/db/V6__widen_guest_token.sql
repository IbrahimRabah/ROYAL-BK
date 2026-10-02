/* =====================================================================
   VELORA — V6: widen cart.guest_token

   Run once against the database you are upgrading (sqlcmd -d <database>).

   Guest tokens used to be a bare UUID (36 characters). Now that the token
   is signed server-side — `<uuid>.<base64url HMAC-SHA256 signature>` — it
   runs to roughly 80 characters, and every save of a guest cart failed
   with "String or binary data would be truncated" against the old
   NVARCHAR(36) column.

   Every step below checks first, so this script is safe to run again on
   a database where it — or the equivalent manual fix — already applied.
   ===================================================================== */

/* ---------------------------------------------------------------------
   1. Drop the filtered unique index and the ownership check constraint.
      Both reference guest_token, so the column cannot be widened while
      either exists.
   --------------------------------------------------------------------- */

IF EXISTS (
    SELECT 1 FROM sys.indexes
    WHERE name = 'ux_cart_guest' AND object_id = OBJECT_ID('dbo.cart')
)
BEGIN
    DROP INDEX ux_cart_guest ON cart;
END
GO

IF EXISTS (
    SELECT 1 FROM sys.check_constraints
    WHERE name = 'ck_cart_owner' AND parent_object_id = OBJECT_ID('dbo.cart')
)
BEGIN
    ALTER TABLE cart DROP CONSTRAINT ck_cart_owner;
END
GO

/* ---------------------------------------------------------------------
   2. Widen the column. 200 leaves headroom well past today's ~80-char
      signed token, in case the signature scheme ever changes.
   --------------------------------------------------------------------- */

ALTER TABLE cart ALTER COLUMN guest_token NVARCHAR(200) NULL;
GO

/* ---------------------------------------------------------------------
   3. Recreate what step 1 removed.
   --------------------------------------------------------------------- */

IF NOT EXISTS (
    SELECT 1 FROM sys.check_constraints
    WHERE name = 'ck_cart_owner' AND parent_object_id = OBJECT_ID('dbo.cart')
)
BEGIN
    ALTER TABLE cart ADD CONSTRAINT ck_cart_owner
        CHECK ([user_id] IS NOT NULL OR [guest_token] IS NOT NULL);
END
GO

/* A guest can hold at most one ACTIVE cart. Filtered so signed-in carts
   (guest_token NULL) and merged/converted guest carts never collide on
   the index. */
IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
    WHERE name = 'ux_cart_guest' AND object_id = OBJECT_ID('dbo.cart')
)
BEGIN
    CREATE UNIQUE INDEX ux_cart_guest ON cart(guest_token)
        WHERE [status] = 'ACTIVE' AND [guest_token] IS NOT NULL;
END
GO

/* ---------------------------------------------------------------------
   Verify
   --------------------------------------------------------------------- */

SELECT c.max_length / 2 AS guest_token_char_length
FROM sys.columns c
WHERE c.object_id = OBJECT_ID('dbo.cart') AND c.name = 'guest_token';

SELECT name AS restored_object, 'index' AS kind
FROM sys.indexes
WHERE object_id = OBJECT_ID('dbo.cart') AND name = 'ux_cart_guest'
UNION ALL
SELECT name, 'check_constraint'
FROM sys.check_constraints
WHERE parent_object_id = OBJECT_ID('dbo.cart') AND name = 'ck_cart_owner';
GO

PRINT 'cart.guest_token widened to NVARCHAR(200). Signed guest tokens save cleanly.';
GO
