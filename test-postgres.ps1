param([switch]$Package)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if (!(Test-Path '.env')) { throw 'Run ./database.ps1 -Init -Start first.' }
$line = Get-Content .env | Where-Object { $_ -match '^POSTGRES_PASSWORD=' } | Select-Object -First 1
if (!$line -or !$line.Substring('POSTGRES_PASSWORD='.Length).Trim()) {
    throw 'POSTGRES_PASSWORD is missing in .env.'
}
$database = 'giavien_test_' + [Guid]::NewGuid().ToString('N')
$previousUrl = $env:TEST_POSTGRES_URL
$previousPassword = $env:TEST_POSTGRES_PASSWORD
$created = $false
try {
    & docker compose exec -T postgres createdb -U giavien $database
    if ($LASTEXITCODE -ne 0) { throw 'Cannot create isolated test database.' }
    $created = $true
    $env:TEST_POSTGRES_URL = "jdbc:postgresql://127.0.0.1:15432/$database"
    $env:TEST_POSTGRES_PASSWORD = $line.Substring('POSTGRES_PASSWORD='.Length)
    $buildArgs = @('clean', 'test')
    if ($Package) { $buildArgs = @('clean', 'package') }
    & ./run.ps1 -MavenArgs $buildArgs
    $testExit = $LASTEXITCODE
} finally {
    $env:TEST_POSTGRES_URL = $previousUrl
    $env:TEST_POSTGRES_PASSWORD = $previousPassword
    if ($created) {
        & docker compose exec -T postgres dropdb -U giavien $database
        if ($LASTEXITCODE -ne 0) { Write-Warning "Could not remove test database $database" }
    }
}
exit $testExit
