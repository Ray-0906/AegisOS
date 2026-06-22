# cleanup.ps1
$ErrorActionPreference = "Continue"

Write-Host "Stopping all java processes (Aegis nodes)..."
Stop-Process -Name "java" -Force -ErrorAction SilentlyContinue

Start-Sleep -Seconds 2

if (Test-Path "aegis_data") {
    Write-Host "Deleting aegis_data directory..."
    Remove-Item -Path "aegis_data" -Recurse -Force
}

Write-Host "Cleanup complete."
