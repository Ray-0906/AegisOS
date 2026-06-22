$ErrorActionPreference = "Stop"

Write-Host "Cleaning up old data..."
if (Test-Path "node1") { Remove-Item -Recurse -Force "node1" }
if (Test-Path "node2") { Remove-Item -Recurse -Force "node2" }
if (Test-Path "node3") { Remove-Item -Recurse -Force "node3" }
if (Test-Path "hello.txt") { Remove-Item -Force "hello.txt" }
if (Test-Path "recovered.txt") { Remove-Item -Force "recovered.txt" }
if (Test-Path "nodes_output.log") { Remove-Item -Force "nodes_output.log" }

Write-Host "Creating test file..."
Set-Content -Path "hello.txt" -Value "This is a test file for node rejoin testing."

Write-Host "Starting Node 1 (Seed)..."
$node1 = Start-Process -FilePath "java" -ArgumentList "-jar", "aegis-cli/target/aegis.jar", "start", "--home", "node1", "--port", "9001" -RedirectStandardOutput "node1_out.log" -RedirectStandardError "node1_err.log" -PassThru -NoNewWindow
Start-Sleep -Seconds 2

Write-Host "Starting Node 2..."
$node2 = Start-Process -FilePath "java" -ArgumentList "-jar", "aegis-cli/target/aegis.jar", "start", "--home", "node2", "--port", "9002", "--seed", "127.0.0.1:9001" -RedirectStandardOutput "node2_out.log" -RedirectStandardError "node2_err.log" -PassThru -NoNewWindow
Start-Sleep -Seconds 2

Write-Host "Starting Node 3..."
$node3 = Start-Process -FilePath "java" -ArgumentList "-jar", "aegis-cli/target/aegis.jar", "start", "--home", "node3", "--port", "9003", "--seed", "127.0.0.1:9001" -RedirectStandardOutput "node3_out.log" -RedirectStandardError "node3_err.log" -PassThru -NoNewWindow
Start-Sleep -Seconds 8

Write-Host "Putting file..."
java -jar aegis-cli/target/aegis.jar put --seed 127.0.0.1:9001 hello.txt "/hello.txt"

Write-Host "Killing Node 3..."
Stop-Process -Id $node3.Id -Force
Start-Sleep -Seconds 10

Write-Host "Restarting Node 3..."
# Append to node3 logs so we can see the full history
$node3_restarted = Start-Process -FilePath "java" -ArgumentList "-jar", "aegis-cli/target/aegis.jar", "start", "--home", "node3", "--port", "9003", "--seed", "127.0.0.1:9001" -RedirectStandardOutput "node3_restarted_out.log" -RedirectStandardError "node3_restarted_err.log" -PassThru -NoNewWindow

Write-Host "Waiting 20 seconds for rejoin and sync..."
Start-Sleep -Seconds 20

Write-Host "Checking nodes list..."
java -jar aegis-cli/target/aegis.jar nodes --seed 127.0.0.1:9001 | Out-File "nodes_output.log"

Write-Host "Getting file..."
java -jar aegis-cli/target/aegis.jar get --seed 127.0.0.1:9001 "/hello.txt" "recovered.txt"

Write-Host "Comparing hashes..."
$hash1 = (Get-FileHash "hello.txt").Hash
$hash2 = (Get-FileHash "recovered.txt").Hash
Write-Host "Original: $hash1"
Write-Host "Recovered: $hash2"

Write-Host "Cleaning up nodes..."
Stop-Process -Id $node1.Id -Force
Stop-Process -Id $node2.Id -Force
Stop-Process -Id $node3_restarted.Id -Force

Write-Host "Test completed."
