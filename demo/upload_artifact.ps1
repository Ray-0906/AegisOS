# upload_artifact.ps1
$ErrorActionPreference = "Stop"

Write-Host "Uploading aegis-examples.jar artifact..."
$output = java -jar aegis-cli\target\aegis.jar artifact upload examples.jar aegis-examples\target\aegis-examples-0.1.0-SNAPSHOT.jar --seed 127.0.0.1:9000

$output
Write-Host "Upload complete."
