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

## Known quirk

`ix_prod_search` (on `product_translation.search_text`) can hold keys up to 2005 bytes against SQL
Server's 1700-byte limit for a non-clustered index, so SQL Server prints a warning when the baseline is
applied, and an insert with a very long `search_text` can fail. The code truncates `search_text` to 1000
characters. The index came from the original schema and is reproduced as it was.
