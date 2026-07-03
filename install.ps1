<#
.SYNOPSIS
Installs the AegisOS CLI globally on Windows.
#>

$ErrorActionPreference = "Stop"

# Define the global installation directory for the user
$InstallDir = "$HOME\.aegis\bin"
Write-Output "Installing AegisOS to $InstallDir..."

# 1. Create the directory if it doesn't exist
if (-not (Test-Path $InstallDir)) {
    New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
}

# 2. Copy the compiled JAR file to the safe installation directory
$SourceJar = ".\aegis-cli\target\aegis.jar"
if (-not (Test-Path $SourceJar)) {
    Write-Error "Could not find aegis.jar. Please run 'mvn clean package -DskipTests' first."
    exit 1
}
Copy-Item -Path $SourceJar -Destination "$InstallDir\aegis.jar" -Force

# 3. Create the native Windows executable wrapper
$BatFile = "$InstallDir\aegis.cmd"
Set-Content -Path $BatFile -Value "@echo off`njava -jar `"$InstallDir\aegis.jar`" %*"

# 4. Inject into the Windows PATH seamlessly
$UserPath = [Environment]::GetEnvironmentVariable("PATH", "User")
if ($UserPath -notmatch [regex]::Escape($InstallDir)) {
    $NewPath = "$UserPath;$InstallDir"
    [Environment]::SetEnvironmentVariable("PATH", $NewPath, "User")
    Write-Output "Successfully added $InstallDir to your PATH."
}

Write-Output "`n========================================="
Write-Output "AEGIS OS INSTALLED SUCCESSFULLY"
Write-Output "========================================="
Write-Output "Please CLOSE this terminal and OPEN A NEW ONE to use the 'aegis' command."