# Database scripts

**The SQL here owns the schema.** `spring.jpa.hibernate.ddl-auto` stays `validate` everywhere. Never
`update`, never `create`: see "Why" below.

## What is in this folder

| File | What it is |
|---|---|
| `baseline/baseline_through_V14.sql` | The **complete schema** — 57 tables, 1 view, every constraint and index — as it stands after V14. Build a new database from this. |
| `baseline/seed_reference_data.sql` | Data the application needs on *any* database: the two roles, the 27 governorates, the shipping zones, per-size rates and which zone each governorate is in. South Sinai, Aswan and Luxor are in no zone, i.e. **closed**. Safe to re-run. |
| `baseline/seed_test_fixtures.sql` | **Tests only.** A seller profile, which invoice issuance requires. Never apply it to a real database: a real shop enters its own legal details at `PUT /api/v1/admin/settings/store-profile`. |
| `V2__...sql` to `V14__...sql` | **History.** Every change made since the schema was first created. Already contained in the baseline. |

There is no `V1`. The original schema script is gone and was never in this repository; the baseline
replaces it, and was scripted from a database that already had V2–V14 applied.

## Rules

- **New changes are `V15__description.sql`, `V16__...`, and so on, in this folder.** Never edit an
  existing `V` script or the baseline.
- **Do not run V2–V14 on a database built from the baseline.** They are in it already.
  They are only for upgrading a database that predates them.
- Scripts contain no `USE <database>`. Choose the database on the command line, so a script can never
  quietly run against the wrong one:

  ```powershell
  sqlcmd -S localhost,1433 -U sa -d royal -f 65001 -b -i src\main\resources\db\V15__something.sql
  ```
- **Arabic columns are `NVARCHAR`**, with `N'...'` literals. Always create databases with
  `COLLATE Arabic_CI_AS`, and pass `-f 65001` to `sqlcmd` for any file that contains Arabic.

## Building a database from scratch

```powershell
sqlcmd -S localhost,1433 -U sa -Q "CREATE DATABASE royal COLLATE Arabic_CI_AS"
sqlcmd -S localhost,1433 -U sa -d royal -f 65001 -b -i src\main\resources\db\baseline\baseline_through_V14.sql
sqlcmd -S localhost,1433 -U sa -d royal -f 65001 -b -i src\main\resources\db\baseline\seed_reference_data.sql
# then V15, V16, ... in order
```

## The test database

The test suite runs against **`royal_test`**, never against `royal`.

```powershell
$env:SQLCMDPASSWORD = '<sa password>'
.\scripts\db\create-test-db.ps1 -WriteTestConfig
```

That drops and recreates `royal_test` from the baseline (plus the seed, the test fixture and any
V15+), creates a login `royal_test_app` that can read and write `royal_test` and **cannot open any other
database**, proves that by trying, and points `src/test/resources/application.yml` (git-ignored) at it.
The script refuses any database name that does not end in `_test`.

Three independent locks keep the tests off a real database:

1. **The login.** The tests connect as `royal_test_app`. SQL Server refuses it everywhere else, so a
   wrong URL fails instead of writing.
2. **`TestDatabaseGuardInitializer`** stops every Spring test context whose configured database name
   does not end in `_test`, before a connection is opened.
3. **`TestDatabaseGuardCheck`** asks the connected server which database it is, and stops the context
   if it is not a `_test` one. `TestDatabaseIsolationIntegrationTest` checks all of this, including that
   every other database on the server refuses the login.

A fresh clone has no `src/test/resources/application.yml`; copy `application.yml.example` there, then
run the script with `-WriteTestConfig` to fill in the login.

## Tools (`scripts/db/`)

| Script | Use |
|---|---|
| `create-test-db.ps1` | (Re)build the test database. See above. |
| `compare-schemas.ps1 -Left A -Right B` | Diffs two databases element by element: columns, types, collations, defaults, checks, indexes, foreign keys, views. Exit 1 if they differ. Run it after building any database from the baseline: `-Left royal_test -Right royal`. |
| `generate-baseline.ps1` | Scripts a database's whole schema. Produced the baseline. |
| `generate-seed.ps1` | Writes `INSERT` statements for chosen tables. Produced the seed files. |
| `data-fingerprint.ps1` | Row count and checksum of every table. Take one before and after something that must not change a database, and compare. |

## Why this exists: `royal` was built by Hibernate

`royal` was first created by letting Hibernate generate the tables (`ddl-auto`), not from SQL. That
looked fine and was wrong in ways that only show up with real data:

- Hibernate maps `String` to `VARCHAR`. **138 of its 172 text columns were `VARCHAR`, on a Latin-1
  collation, including every column that holds Arabic — so Arabic was stored as `?`**: names, categories,
  addresses, the shop's legal name. It cannot be recovered.
- It created **none of the unique constraints** the code relies on (`uq_prod_slug`, `uq_var_sku`,
  `ux_user_phone`, …): the database would accept two accounts with the same phone number.
- Only 20 of the 112 default constraints and 44 of the 57 tables existed.

On **2026-10-02** `royal` was dropped and rebuilt from this baseline, after a `BACKUP DATABASE`
(`royal_before_rebuild_20261002.bak`, verified with `RESTORE VERIFYONLY` and by restoring it and
comparing every table's checksum). The admin account, its roles and the shop's non-Arabic details were
carried over. The shop's Arabic legal name and address were already `?????` and were **not** carried
over: enter them at `PUT /api/v1/admin/settings/store-profile`.

## Known issues

### Warning when the baseline is applied: `ix_prod_search` … 1700 bytes

Building a database from the baseline prints this, and it is **expected**:

```
Warning! The maximum key length for a nonclustered index is 1700 bytes. The index 'ix_prod_search'
has maximum length of 2005 bytes. For some combination of large values, the insert/update operation
will fail.
```

It is only a warning; the index is created and the script succeeds. Do not "fix" it by editing the
baseline.

**What it means.** `ix_prod_search` is on `product_translation (locale, search_text)`.
`search_text` is `NVARCHAR(1000)`, which is up to 2000 bytes, plus the 5-byte `locale`: the index
*could* hold an entry of 2005 bytes, and SQL Server limits one to 1700.

**Measured limit** (on `royal_test`, Arabic text, 2 bytes per character): a `search_text` of **849
characters inserts; 850 fails** with error 1946 *"The index entry of length 1702 bytes for the index
'ix_prod_search' exceeds the maximum allowed length of 1700 bytes"*.

**Why it cannot happen today.** `search_text` is built as `name + " " + shortDescription`, and the API
limits those to 255 and 500 characters (`TranslationRequest`), so it is **at most 756 characters**,
comfortably under 849. (`ProductAdminService.buildSearchText` also truncates to 1000 characters, but
that is *not* what protects the index: 1000 characters is more than the index accepts. The 255 / 500
validation is.)

**When it would bite.** If either limit is raised so that the two can add up to 850 or more, or if a
row is written by a script that bypasses the API. Saving the product would then fail with error 1946.
If that is ever needed, the fix is one of: truncate `search_text` to 800 characters in
`buildSearchText`; or shrink the column to `NVARCHAR(800)` in a new `V15`; or index only
`search_text`'s first part. It was not done now because nothing can reach the limit.
