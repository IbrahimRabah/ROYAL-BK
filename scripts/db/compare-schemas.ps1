<#
.SYNOPSIS
  Diffs the schema of two databases element by element (columns, types, collations,
  defaults, checks, indexes, foreign keys, views). Exit code 1 if they differ.

  Used to prove that a database built from baseline_through_V14.sql is the same schema
  as the one the baseline was scripted from:
    .\scripts\db\compare-schemas.ps1 -Left velora -Right royal_test
#>
param(
    [Parameter(Mandatory)] [string] $Left,
    [Parameter(Mandatory)] [string] $Right,
    [string] $Server = 'localhost,1433',
    [string] $User = 'sa',
    [string] $Password = $env:SQLCMDPASSWORD
)

$ErrorActionPreference = 'Stop'
$script = Join-Path $PSScriptRoot 'schema-fingerprint.sql'
$env:SQLCMDPASSWORD = $Password

function Get-Fingerprint([string] $database) {
    $lines = & sqlcmd -S $Server -U $User -C -I -d $database -b -h -1 -W -w 8000 -i $script
    if ($LASTEXITCODE -ne 0) { throw "sqlcmd failed for ${database}: $($lines -join ' ')" }
    $lines = $lines | Where-Object { $_ -and $_.Trim() }
    # An empty or tiny fingerprint means the query broke, not that the schemas agree.
    if ($lines.Count -lt 100) { throw "Fingerprint of $database has only $($lines.Count) element(s); refusing to compare: $($lines -join ' ')" }
    return $lines
}

$a = Get-Fingerprint $Left
$b = Get-Fingerprint $Right
"{0}: {1} schema elements   {2}: {3} schema elements" -f $Left, $a.Count, $Right, $b.Count

$diff = Compare-Object -ReferenceObject $a -DifferenceObject $b
if ($diff) {
    $diff | ForEach-Object {
        $side = if ($_.SideIndicator -eq '<=') { "only in $Left " } else { "only in $Right" }
        "{0}: {1}" -f $side, $_.InputObject
    }
    "SCHEMAS DIFFER ($($diff.Count) element(s))"
    exit 1
}
"IDENTICAL: every column, type, collation, default, check, index, foreign key and view matches."



