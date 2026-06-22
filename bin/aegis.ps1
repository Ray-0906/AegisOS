# aegis.ps1
# Wrapper script for AegisOS CLI

$aegisJar = Join-Path $PSScriptRoot "..\aegis-cli\target\aegis.jar"

if (-Not (Test-Path $aegisJar)) {
    Write-Host "Error: CLI jar not found at $aegisJar" -ForegroundColor Red
    Write-Host "Have you run 'mvn clean package'?"
    exit 1
}

java -jar $aegisJar $args
