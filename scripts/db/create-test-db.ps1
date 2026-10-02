<#
.SYNOPSIS
  (Re)creates the isolated database the test suite runs against, from the committed baseline.

.DESCRIPTION
  1. Drops and recreates <Database> (default royal_test) with COLLATE Arabic_CI_AS.
     Refuses any name that does not end in "_test", so this script can never be pointed at a
     real database by mistake.
  2. Applies src/main/resources/db/baseline/baseline_through_V14.sql, then the reference seed,
     then the test fixture (a seller profile that invoice issuance requires).
  3. Creates a login just for the tests, with read/write on <Database> and no access to any
     other database. The tests connect as that login, so they cannot touch the real database
     even if a URL is wrong: SQL Server refuses the connection.
  4. Proves it: connects as that login to every other user database and expects a refusal.

  Needs an administrative login in SQLCMDPASSWORD (default user sa). Anything newer than V14
  - V15 and later - is applied on top, in order, after step 2.

    $env:SQLCMDPASSWORD = '<sa password>'
    .\scripts\db\create-test-db.ps1 -WriteTestConfig

.PARAMETER WriteTestConfig
  Points src/test/resources/application.yml (git-ignored, local) at the new database and login.
#>
param(
    [string] $Database = 'royal_test',
    [string] $Server = 'localhost,1433',
    [string] $AdminUser = 'sa',
    [string] $AdminPassword = $env:SQLCMDPASSWORD,
    [string] $TestLogin = 'royal_test_app',
    [string] $TestPassword,
    [switch] $WriteTestConfig
)

$ErrorActionPreference = 'Stop'
if ($Database -notmatch '_test$') {
    throw "Refusing to (re)create '$Database': a test database name must end in '_test'."
}
if (-not $AdminPassword) { throw 'Set SQLCMDPASSWORD to the administrative login password.' }

$repo = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$dbDir = Join-Path $repo 'src\main\resources\db'
$baseline = Join-Path $dbDir 'baseline\baseline_through_V14.sql'
$seed = Join-Path $dbDir 'baseline\seed_reference_data.sql'
$fixtures = Join-Path $dbDir 'baseline\seed_test_fixtures.sql'
foreach ($f in $baseline, $seed, $fixtures) { if (-not (Test-Path $f)) { throw "Missing $f" } }

function Invoke-Sql([string] $database, [string] $query) {
    & sqlcmd -S $Server -U $AdminUser -P $AdminPassword -C -I -b -d $database -Q $query
    if ($LASTEXITCODE -ne 0) { throw "sqlcmd failed: $query" }
}
function Invoke-SqlFile([string] $database, [string] $file) {
    & sqlcmd -S $Server -U $AdminUser -P $AdminPassword -C -I -b -f 65001 -d $database -i $file
    if ($LASTEXITCODE -ne 0) { throw "sqlcmd failed on $file" }
}

if (-not $TestPassword) {
    $bytes = New-Object byte[] 24
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'.ToCharArray()
    $TestPassword = -join ($bytes | ForEach-Object { $alphabet[$_ % $alphabet.Length] })
}

"== 1. Database $Database (COLLATE Arabic_CI_AS)"
Invoke-Sql 'master' "IF DB_ID(N'$Database') IS NOT NULL BEGIN ALTER DATABASE [$Database] SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE [$Database]; END; CREATE DATABASE [$Database] COLLATE Arabic_CI_AS;"

"== 2. Baseline, reference data, test fixture"
Invoke-SqlFile $Database $baseline
Invoke-SqlFile $Database $seed
Invoke-SqlFile $Database $fixtures

# Anything after V14 is not in the baseline: apply it, in numeric order (V15 before V100).
$later = Get-ChildItem $dbDir -Filter 'V*__*.sql' |
    Where-Object { $_.Name -match '^V(\d+)__' -and [int]$Matches[1] -ge 15 } |
    Sort-Object { [int]([regex]::Match($_.Name, '^V(\d+)__').Groups[1].Value) }
foreach ($migration in $later) {
    "   applying $($migration.Name)"
    Invoke-SqlFile $Database $migration.FullName
}

"== 3. Login $TestLogin (read/write on $Database only)"
$escaped = $TestPassword.Replace("'", "''")
Invoke-Sql 'master' "IF SUSER_ID(N'$TestLogin') IS NULL CREATE LOGIN [$TestLogin] WITH PASSWORD = N'$escaped', CHECK_POLICY = OFF, DEFAULT_DATABASE = [master]; ELSE ALTER LOGIN [$TestLogin] WITH PASSWORD = N'$escaped';"
Invoke-Sql $Database "CREATE USER [$TestLogin] FOR LOGIN [$TestLogin]; ALTER ROLE db_datareader ADD MEMBER [$TestLogin]; ALTER ROLE db_datawriter ADD MEMBER [$TestLogin];"

"== 4. Proof: the test login can reach $Database and nothing else"
$ok = & sqlcmd -S $Server -U $TestLogin -P $TestPassword -C -I -b -d $Database -h -1 -W -Q "SET NOCOUNT ON; SELECT DB_NAME()"
if ($LASTEXITCODE -ne 0 -or ($ok | Select-Object -First 1) -ne $Database) { throw "The test login cannot use $Database" }
"   connects to $Database : yes"
$others = & sqlcmd -S $Server -U $AdminUser -P $AdminPassword -C -I -h -1 -W -Q "SET NOCOUNT ON; SELECT name FROM sys.databases WHERE database_id > 4 AND name <> N'$Database'"
foreach ($other in $others) {
    $other = $other.Trim()
    if (-not $other) { continue }
    # A refusal is the EXPECTED result, and sqlcmd reports it on stderr, which PowerShell turns into
    # an exception under 'Stop'. So this one call is allowed to write errors, and only the exit code counts.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & sqlcmd -S $Server -U $TestLogin -P $TestPassword -C -I -b -d $other -Q "SELECT 1" 2>&1 | Out-Null
    $connectCode = $LASTEXITCODE
    $ErrorActionPreference = $previous
    if ($connectCode -eq 0) { throw "The test login CAN open database '$other'. It must not." }
    "   connects to $other : refused"
}

if ($WriteTestConfig) {
    $yml = Join-Path $repo 'src\test\resources\application.yml'
    if (-not (Test-Path $yml)) { throw "No $yml to update; copy application.yml.example there first." }
    $text = [System.IO.File]::ReadAllText($yml, (New-Object System.Text.UTF8Encoding($false)))
    $text = [regex]::Replace($text, 'databaseName=[A-Za-z0-9_]+', "databaseName=$Database")
    # Only the datasource's username and password are replaced: each is the FIRST one in the file.
    # The mail section further down has a username and a password of its own.
    $firstUser = [regex]::Match($text, '(?m)^(\s*username:\s*).*$')
    $text = $text.Remove($firstUser.Index, $firstUser.Length).Insert($firstUser.Index, $firstUser.Groups[1].Value + $TestLogin)
    $first = [regex]::Match($text, '(?m)^(\s*password:\s*).*$')
    $text = $text.Remove($first.Index, $first.Length).Insert($first.Index, $first.Groups[1].Value + $TestPassword)
    [System.IO.File]::WriteAllText($yml, $text, (New-Object System.Text.UTF8Encoding($false)))
    "== Wrote $yml (git-ignored): database $Database, login $TestLogin"
} else {
    "== Test login password (shown once): $TestPassword"
}
"Done."


