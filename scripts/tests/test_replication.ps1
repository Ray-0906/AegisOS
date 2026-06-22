$ErrorActionPreference = "Stop"

Write-Host "Cleaning up old data..."
if (Test-Path "node1") { Remove-Item -Recurse -Force "node1" }
if (Test-Path "node2") { Remove-Item -Recurse -Force "node2" }
if (Test-Path "node3") { Remove-Item -Recurse -Force "node3" }
if (Test-Path "test.txt") { Remove-Item -Force "test.txt" }
if (Test-Path "recovered.txt") { Remove-Item -Force "recovered.txt" }

Write-Host "Creating test file..."
Set-Content -Path "test.txt" -Value "This is a test file for AegisFS replication validation."

Write-Host "Starting Node 1 (Seed)..."
$node1 = Start-Process -FilePath "java" -ArgumentList "-jar", "aegis-cli/target/aegis.jar", "start", "--home", "node1", "--port", "9001" -PassThru -NoNewWindow
Start-Sleep -Seconds 2

Write-Host "Starting Node 2..."
$node2 = Start-Process -FilePath "java" -ArgumentList "-jar", "aegis-cli/target/aegis.jar", "start", "--home", "node2", "--port", "9002", "--seed", "127.0.0.1:9001" -PassThru -NoNewWindow
Start-Sleep -Seconds 2

Write-Host "Starting Node 3..."
$node3 = Start-Process -FilePath "java" -ArgumentList "-jar", "aegis-cli/target/aegis.jar", "start", "--home", "node3", "--port", "9003", "--seed", "127.0.0.1:9001" -PassThru -NoNewWindow
Start-Sleep -Seconds 5

Write-Host "Putting file..."
java -jar aegis-cli/target/aegis.jar put --seed 127.0.0.1:9001 test.txt "/test.txt"

Write-Host "Waiting 10 seconds for replication..."
Start-Sleep -Seconds 10

Write-Host "Killing Node 3..."
Stop-Process -Id $node3.Id -Force
Start-Sleep -Seconds 5

Write-Host "Getting file..."
java -jar aegis-cli/target/aegis.jar get --seed 127.0.0.1:9001 "/test.txt" "recovered.txt"

Write-Host "Comparing files..."
$hash1 = (Get-FileHash "test.txt").Hash
$hash2 = (Get-FileHash "recovered.txt").Hash
Write-Host "Original hash: $hash1"
Write-Host "Recovered hash: $hash2"

if ($hash1 -eq $hash2) {
    Write-Host "SUCCESS: Files match."
} else {
    Write-Host "FAILURE: Files do not match."
}

Write-Host "Cleaning up processes..."
Stop-Process -Id $node1.Id -Force
Stop-Process -Id $node2.Id -Force

Write-Host "Test completed."
