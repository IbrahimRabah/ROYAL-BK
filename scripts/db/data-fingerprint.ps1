<#
.SYNOPSIS
  A fingerprint of a database's DATA: for every table, its row count and a checksum of all its
  rows. Take one before and one after something that must not have changed the database, and
  compare - this is how "the test suite never touches the real database" is shown, rather than
  assumed.

    .\scripts\db\data-fingerprint.ps1 -Database royal -Output before.txt
    ...run the suite...
    .\scripts\db\data-fingerprint.ps1 -Database royal -Output after.txt
    fc.exe before.txt after.txt

  Reads only. Exit code is 0 and the file is written; comparing is up to the caller.
#>
param(
    [Parameter(Mandatory)] [string] $Database,
    [Parameter(Mandatory)] [string] $Output,
    [string] $Server = 'localhost,1433',
    [string] $User = 'sa',
    [string] $Password = $env:SQLCMDPASSWORD
)

$ErrorActionPreference = 'Stop'
$query = @"
SET NOCOUNT ON;
DECLARE @sql nvarchar(max) = N'';
SELECT @sql += N'SELECT N''' + t.name + N''' AS tbl, COUNT_BIG(*) AS row_count, '
             + N'ISNULL(CHECKSUM_AGG(BINARY_CHECKSUM(*)), 0) AS row_checksum FROM dbo.' + QUOTENAME(t.name) + N' UNION ALL '
FROM sys.tables t;
SET @sql = LEFT(@sql, LEN(@sql) - 10) + N' ORDER BY 1';
EXEC sp_executesql @sql;
"@
$lines = & sqlcmd -S $Server -U $User -P $Password -C -I -b -d $Database -h -1 -W -s '|' -Q $query
if ($LASTEXITCODE -ne 0) { throw "sqlcmd failed for $Database" }
$lines = $lines | Where-Object { $_ -and $_.Trim() }
if ($lines.Count -lt 10) { throw "Fingerprint of $Database has only $($lines.Count) line(s): $($lines -join ' ')" }
$lines | Set-Content $Output -Encoding UTF8
"$Database : $($lines.Count) tables fingerprinted, $((($lines | ForEach-Object { [long](($_ -split '\|')[1]) }) | Measure-Object -Sum).Sum) rows in total -> $Output"
