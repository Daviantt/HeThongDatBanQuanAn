param([switch]$Test, [switch]$Package)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$bundledJdk = Get-ChildItem "$PSScriptRoot/.tools/jdk" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if ($bundledJdk) { $env:JAVA_HOME = $bundledJdk.FullName; $env:PATH = "$env:JAVA_HOME/bin;$env:PATH" }
$mavenExe = "$PSScriptRoot/.tools/maven/bin/mvn.cmd"
if (!(Test-Path $mavenExe)) { $mavenExe = 'mvn' }
$arguments = @('-B', '-Dmaven.repo.local=.tools/repository')
if ($Test) { $arguments += 'test' } elseif ($Package) { $arguments += 'package' } else { $arguments += 'spring-boot:run' }
& $mavenExe @arguments
exit $LASTEXITCODE
