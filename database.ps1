param(
    [switch]$Init, [switch]$Start, [switch]$Status
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if ($Init) {
    if ((Test-Path '.env') -or (Test-Path 'config/database.properties')) {
        throw 'Local configuration already exists; refusing to overwrite credentials.'
    }
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $password = -join ($bytes | ForEach-Object { $_.ToString('x2') })
    [IO.File]::WriteAllText("$PSScriptRoot/.env", "POSTGRES_PASSWORD=$password`n")
    $properties = 'spring.datasource.url=${DATABASE_URL:jdbc:postgresql://127.0.0.1:15432/giavien}' + "`n" +
        'spring.datasource.username=${DATABASE_USER:giavien}' + "`n" +
        'spring.datasource.password=${DATABASE_PASSWORD:' + $password + "}`n"
    [IO.File]::WriteAllText("$PSScriptRoot/config/database.properties", $properties)
    Write-Host 'Created .env and config/database.properties (ignored by Git).'
}
if ($Start) {
    & docker compose up -d --wait postgres
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL did not start. Check Docker Desktop.' }
}
if ($Status) {
    & docker compose ps
    if ($LASTEXITCODE -ne 0) { throw 'Cannot query Docker.' }
}
if (!$Init -and !$Start -and !$Status) {
    Write-Host 'Usage: ./database.ps1 -Init | -Start | -Status'
}
