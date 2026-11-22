$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$portableJdk = Get-ChildItem -LiteralPath (Join-Path $projectRoot '.tools') -Directory -Filter 'jdk-*' -ErrorAction SilentlyContinue | Select-Object -First 1
$portableMaven = Join-Path $projectRoot '.tools\apache-maven-3.9.11\bin\mvn.cmd'
if ($portableJdk) { $env:JAVA_HOME = $portableJdk.FullName }
$mavenCommand = if (Test-Path -LiteralPath $portableMaven) { $portableMaven } else { 'mvn' }
Push-Location $projectRoot
try {
    & $mavenCommand --batch-mode --no-transfer-progress clean verify
    if ($LASTEXITCODE -ne 0) { throw 'La compilación ha fallado. Consulta el mensaje anterior.' }
    Write-Host 'Listo: target\MinecraftIA-1.2.0.jar'
} finally { Pop-Location }
