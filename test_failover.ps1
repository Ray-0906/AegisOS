$ErrorActionPreference = "Stop"

Write-Host "Cleaning up old data..."
if (Test-Path "node1") { Remove-Item -Recurse -Force "node1" }
if (Test-Path "node2") { Remove-Item -Recurse -Force "node2" }
if (Test-Path "node3") { Remove-Item -Recurse -Force "node3" }
if (Test-Path "before.txt") { Remove-Item -Force "before.txt" }
if (Test-Path "after.txt") { Remove-Item -Force "after.txt" }
if (Test-Path "before_recovered.txt") { Remove-Item -Force "before_recovered.txt" }
if (Test-Path "after_recovered.txt") { Remove-Item -Force "after_recovered.txt" }
if (Test-Path "nodes_output.log") { Remove-Item -Force "nodes_output.log" }

Write-Host "Creating test files..."
Set-Content -Path "before.txt" -Value "This is BEFORE the leader dies."
Set-Content -Path "after.txt" -Value "This is AFTER the leader dies."

Write-Host "Starting Node 1 (Seed)..."
$node1 = Start-Process -FilePath "java" -ArgumentList "-jar", "aegis-cli/target/aegis.jar", "start", "--home", "node1", "--port", "9001" -RedirectStandardOutput "node1_out.log" -RedirectStandardError "node1_err.log" -PassThru -NoNewWindow
Start-Sleep -Seconds 2

Write-Host "Starting Node 2..."
$node2 = Start-Process -FilePath "java" -ArgumentList "-jar", "aegis-cli/target/aegis.jar", "start", "--home", "node2", "--port", "9002", "--seed", "127.0.0.1:9001" -RedirectStandardOutput "node2_out.log" -RedirectStandardError "node2_err.log" -PassThru -NoNewWindow
Start-Sleep -Seconds 2

Write-Host "Starting Node 3..."
$node3 = Start-Process -FilePath "java" -ArgumentList "-jar", "aegis-cli/target/aegis.jar", "start", "--home", "node3", "--port", "9003", "--seed", "127.0.0.1:9001" -RedirectStandardOutput "node3_out.log" -RedirectStandardError "node3_err.log" -PassThru -NoNewWindow
Start-Sleep -Seconds 8

Write-Host "Putting BEFORE file..."
java -jar aegis-cli/target/aegis.jar put --seed 127.0.0.1:9001 before.txt "/before.txt"
Start-Sleep -Seconds 5

Write-Host "Finding leader..."
# Search for 'became LEADER' in logs
$leaderNode = ""
$leaderId = ""
$log1 = Get-Content "node1_out.log" -Tail 50 | Select-String "became LEADER"
$log2 = Get-Content "node2_out.log" -Tail 50 | Select-String "became LEADER"
$log3 = Get-Content "node3_out.log" -Tail 50 | Select-String "became LEADER"

if ($log1) { $leaderNode = "node1"; $leaderProc = $node1 }
elseif ($log2) { $leaderNode = "node2"; $leaderProc = $node2 }
elseif ($log3) { $leaderNode = "node3"; $leaderProc = $node3 }

Write-Host "Detected Leader: $leaderNode"

Write-Host "Killing the leader ($leaderNode)..."
if ($leaderProc) {
    Stop-Process -Id $leaderProc.Id -Force
} else {
    Write-Host "COULD NOT FIND LEADER. Aborting."
    Stop-Process -Id $node1.Id -Force
    Stop-Process -Id $node2.Id -Force
    Stop-Process -Id $node3.Id -Force
    exit 1
}

Write-Host "Waiting 10 seconds for new election..."
Start-Sleep -Seconds 10

Write-Host "Putting AFTER file..."
# Use a surviving node as seed. 9001 might be dead if it was the leader.
$seedPort = "9001"
if ($leaderNode -eq "node1") { $seedPort = "9002" }
java -jar aegis-cli/target/aegis.jar put --seed 127.0.0.1:$seedPort after.txt "/after.txt"
Start-Sleep -Seconds 5

Write-Host "Getting BOTH files..."
java -jar aegis-cli/target/aegis.jar get --seed 127.0.0.1:$seedPort "/before.txt" "before_recovered.txt"
java -jar aegis-cli/target/aegis.jar get --seed 127.0.0.1:$seedPort "/after.txt" "after_recovered.txt"

Write-Host "Comparing hashes..."
$hashB1 = (Get-FileHash "before.txt").Hash
$hashB2 = (Get-FileHash "before_recovered.txt").Hash
Write-Host "BEFORE Original: $hashB1"
Write-Host "BEFORE Recovered: $hashB2"

$hashA1 = (Get-FileHash "after.txt").Hash
$hashA2 = (Get-FileHash "after_recovered.txt").Hash
Write-Host "AFTER Original: $hashA1"
Write-Host "AFTER Recovered: $hashA2"

Write-Host "Cleaning up remaining nodes..."
if ($leaderNode -ne "node1") { Stop-Process -Id $node1.Id -Force }
if ($leaderNode -ne "node2") { Stop-Process -Id $node2.Id -Force }
if ($leaderNode -ne "node3") { Stop-Process -Id $node3.Id -Force }

Write-Host "Test completed."
