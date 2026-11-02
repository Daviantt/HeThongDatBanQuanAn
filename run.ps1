param([switch]$Test, [switch]$Package, [string[]]$MavenArgs)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if (!$MavenArgs -and ($Test -or $Package)) {
    & "$PSScriptRoot/test-postgres.ps1" -Package:($Package -and !$Test)
    exit $LASTEXITCODE
}
$bundledJdk = Get-ChildItem "$PSScriptRoot/.tools/jdk" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if ($bundledJdk) { $env:JAVA_HOME = $bundledJdk.FullName; $env:PATH = "$env:JAVA_HOME/bin;$env:PATH" }
if (!$env:JAVA_HOME -and !(Get-Command java -ErrorAction SilentlyContinue)) {
    $ideaJdk = Get-ChildItem "$env:USERPROFILE/.jdks" -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path "$($_.FullName)/bin/java.exe" } | Sort-Object Name -Descending | Select-Object -First 1
    if ($ideaJdk) { $env:JAVA_HOME = $ideaJdk.FullName }
}
if ($env:JAVA_HOME) { $env:PATH = "$env:JAVA_HOME/bin;$env:PATH" }
$mavenExe = "$PSScriptRoot/.tools/maven/bin/mvn.cmd"
if (!(Test-Path $mavenExe)) {
    if ($env:MAVEN_HOME) { $mavenExe = "$env:MAVEN_HOME/bin/mvn.cmd" } else { $mavenExe = 'mvn' }
}
$arguments = @('-B')
if (Test-Path "$PSScriptRoot/.tools/repository") { $arguments += '-Dmaven.repo.local=.tools/repository' }
if ($MavenArgs) { $arguments += $MavenArgs } else { $arguments += 'spring-boot:run' }
& $mavenExe @arguments
exit $LASTEXITCODE
