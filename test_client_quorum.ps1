$ErrorActionPreference = "Stop"

Write-Host "Cleaning up old data..."
if (Test-Path "node1") { Remove-Item -Recurse -Force "node1" }
if (Test-Path "node2") { Remove-Item -Recurse -Force "node2" }
if (Test-Path "node3") { Remove-Item -Recurse -Force "node3" }
if (Test-Path "dummy.txt") { Remove-Item -Force "dummy.txt" }
if (Test-Path "recovered.txt") { Remove-Item -Force "recovered.txt" }

Write-Host "Creating dummy file..."
Set-Content -Path "dummy.txt" -Value "This is a test file for AegisFS."

Write-Host "Starting Node 1 (Seed)..."
$node1 = Start-Process -FilePath "java" -ArgumentList "-jar", "aegis-cli/target/aegis.jar", "start", "--home", "node1", "--port", "9001" -PassThru -NoNewWindow
Start-Sleep -Seconds 2

Write-Host "Starting Node 2..."
$node2 = Start-Process -FilePath "java" -ArgumentList "-jar", "aegis-cli/target/aegis.jar", "start", "--home", "node2", "--port", "9002", "--seed", "127.0.0.1:9001" -PassThru -NoNewWindow
Start-Sleep -Seconds 2

Write-Host "Starting Node 3..."
$node3 = Start-Process -FilePath "java" -ArgumentList "-jar", "aegis-cli/target/aegis.jar", "start", "--home", "node3", "--port", "9003", "--seed", "127.0.0.1:9001" -PassThru -NoNewWindow
Start-Sleep -Seconds 5

Write-Host "Running transient clients (6 operations)..."
for ($i = 1; $i -le 3; $i++) {
    Write-Host "Operation $i (PUT)..."
    java -jar aegis-cli/target/aegis.jar put --seed 127.0.0.1:9001 dummy.txt "/test_file_$i.txt"
    Start-Sleep -Seconds 1
    
    Write-Host "Operation $i (GET)..."
    java -jar aegis-cli/target/aegis.jar get --seed 127.0.0.1:9002 "/test_file_$i.txt" "recovered_$i.txt"
    Start-Sleep -Seconds 1
}

Write-Host "Killing Node 3..."
Stop-Process -Id $node3.Id -Force
Start-Sleep -Seconds 5

Write-Host "Verifying cluster still works after node 3 failure..."
Write-Host "Running PUT..."
java -jar aegis-cli/target/aegis.jar put --seed 127.0.0.1:9001 dummy.txt "/test_file_after_kill.txt"

Write-Host "Running GET..."
java -jar aegis-cli/target/aegis.jar get --seed 127.0.0.1:9002 "/test_file_after_kill.txt" "recovered_after_kill.txt"

Write-Host "Cleaning up processes..."
Stop-Process -Id $node1.Id -Force
Stop-Process -Id $node2.Id -Force

Write-Host "Test completed successfully."
