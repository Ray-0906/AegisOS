$ErrorActionPreference = "Stop"

Write-Host "Cleaning up old data..."
if (Test-Path "node1") { Remove-Item -Recurse -Force "node1" }
if (Test-Path "node2") { Remove-Item -Recurse -Force "node2" }
if (Test-Path "node3") { Remove-Item -Recurse -Force "node3" }
if (Test-Path "file1.txt") { Remove-Item -Force "file1.txt" }
if (Test-Path "file2.txt") { Remove-Item -Force "file2.txt" }
if (Test-Path "recovered1.txt") { Remove-Item -Force "recovered1.txt" }
if (Test-Path "recovered2.txt") { Remove-Item -Force "recovered2.txt" }

Write-Host "Creating test files..."
Set-Content -Path "file1.txt" -Value "File 1: Before node3 dies."
Set-Content -Path "file2.txt" -Value "File 2: While node3 is offline."

Write-Host "Starting Node 1 (Seed)..."
$node1 = Start-Process -FilePath "java" -ArgumentList "-jar", "aegis-cli/target/aegis.jar", "start", "--home", "node1", "--port", "9001" -RedirectStandardOutput "node1_out.log" -RedirectStandardError "node1_err.log" -PassThru -NoNewWindow
Start-Sleep -Seconds 2

Write-Host "Starting Node 2..."
$node2 = Start-Process -FilePath "java" -ArgumentList "-jar", "aegis-cli/target/aegis.jar", "start", "--home", "node2", "--port", "9002", "--seed", "127.0.0.1:9001" -RedirectStandardOutput "node2_out.log" -RedirectStandardError "node2_err.log" -PassThru -NoNewWindow
Start-Sleep -Seconds 2

Write-Host "Starting Node 3..."
$node3 = Start-Process -FilePath "java" -ArgumentList "-jar", "aegis-cli/target/aegis.jar", "start", "--home", "node3", "--port", "9003", "--seed", "127.0.0.1:9001" -RedirectStandardOutput "node3_out.log" -RedirectStandardError "node3_err.log" -PassThru -NoNewWindow
Start-Sleep -Seconds 8

Write-Host "Putting file 1..."
java -jar aegis-cli/target/aegis.jar put --seed 127.0.0.1:9001 file1.txt "/file1.txt"
Start-Sleep -Seconds 5

Write-Host "Killing Node 3..."
Stop-Process -Id $node3.Id -Force
Start-Sleep -Seconds 10

Write-Host "Putting file 2 (while node3 is dead)..."
java -jar aegis-cli/target/aegis.jar put --seed 127.0.0.1:9001 file2.txt "/file2.txt"
Start-Sleep -Seconds 5

Write-Host "Restarting Node 3..."
$node3_restarted = Start-Process -FilePath "java" -ArgumentList "-jar", "aegis-cli/target/aegis.jar", "start", "--home", "node3", "--port", "9003", "--seed", "127.0.0.1:9001" -RedirectStandardOutput "node3_restarted_out.log" -RedirectStandardError "node3_restarted_err.log" -PassThru -NoNewWindow
Start-Sleep -Seconds 15

Write-Host "Getting files..."
java -jar aegis-cli/target/aegis.jar get --seed 127.0.0.1:9001 "/file1.txt" "recovered1.txt"
java -jar aegis-cli/target/aegis.jar get --seed 127.0.0.1:9001 "/file2.txt" "recovered2.txt"

Write-Host "Comparing hashes..."
$hash1a = (Get-FileHash "file1.txt").Hash
$hash1b = (Get-FileHash "recovered1.txt").Hash
Write-Host "File1 Original: $hash1a"
Write-Host "File1 Recovered: $hash1b"

$hash2a = (Get-FileHash "file2.txt").Hash
$hash2b = (Get-FileHash "recovered2.txt").Hash
Write-Host "File2 Original: $hash2a"
Write-Host "File2 Recovered: $hash2b"

Write-Host "Cleaning up nodes..."
Stop-Process -Id $node1.Id -Force
Stop-Process -Id $node2.Id -Force
Stop-Process -Id $node3_restarted.Id -Force

Write-Host "Test completed."
