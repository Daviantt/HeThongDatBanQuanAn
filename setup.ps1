$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
New-Item -ItemType Directory -Path .tools -Force | Out-Null
if (!(Test-Path '.tools/jdk')) {
    Write-Host 'Downloading Temurin JDK 21 from Adoptium...'
    $assets = Invoke-RestMethod 'https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'
    $package = $assets[0].binary.package
    Invoke-WebRequest -Uri $package.link -OutFile '.tools/jdk.zip'
    $actualHash = (Get-FileHash '.tools/jdk.zip' -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $package.checksum.ToLowerInvariant()) { throw 'JDK checksum mismatch.' }
    Expand-Archive -LiteralPath '.tools/jdk.zip' -DestinationPath '.tools/jdk'
}
if (!(Test-Path '.tools/maven/bin/mvn.cmd')) {
    Write-Host 'Downloading Maven 3.9.14 from Maven Central...'
    $archiveUrl = 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.14/apache-maven-3.9.14-bin.zip'
    Invoke-WebRequest -Uri $archiveUrl -OutFile '.tools/maven.zip'
    $expectedHash = (Invoke-WebRequest -Uri ($archiveUrl + '.sha512')).Content.Trim()
    $actualHash = (Get-FileHash '.tools/maven.zip' -Algorithm SHA512).Hash.ToLowerInvariant()
    if ($actualHash -ne $expectedHash.ToLowerInvariant()) { throw 'Maven checksum mismatch.' }
    Expand-Archive -LiteralPath '.tools/maven.zip' -DestinationPath '.tools/maven-unpack' -Force
    Copy-Item -LiteralPath '.tools/maven-unpack/apache-maven-3.9.14' -Destination '.tools/maven' -Recurse -Force
}
Write-Host 'Ready. Run: .\run.ps1'
