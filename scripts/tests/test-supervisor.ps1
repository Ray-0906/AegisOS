$ErrorActionPreference = "Stop"

Write-Host "Cleaning up old data..."
if (Test-Path test-cluster-n1) { Remove-Item -Recurse -Force test-cluster-n1 }
if (Test-Path test-cluster-n2) { Remove-Item -Recurse -Force test-cluster-n2 }
if (Test-Path test-cluster-n3) { Remove-Item -Recurse -Force test-cluster-n3 }
Stop-Process -Name java -Force -ErrorAction SilentlyContinue

Write-Host "Starting Node 1 (Bootstrap)..."
Start-Process -FilePath "cmd.exe" -ArgumentList "/c", "java -jar aegis-cli\target\aegis.jar start --port 7000 --metrics-port 20000 --rest-port 20001 --home test-cluster-n1 --bootstrap > node1.log 2>&1" -NoNewWindow

Start-Sleep -Seconds 5

Write-Host "Starting Node 2 (Peer)..."
Start-Process -FilePath "cmd.exe" -ArgumentList "/c", "java -jar aegis-cli\target\aegis.jar start --port 7002 --metrics-port 20002 --rest-port 20003 --home test-cluster-n2 --seed 127.0.0.1:7000 > node2.log 2>&1" -NoNewWindow

Write-Host "Starting Node 3 (Peer)..."
Start-Process -FilePath "cmd.exe" -ArgumentList "/c", "java -jar aegis-cli\target\aegis.jar start --port 7004 --metrics-port 20004 --rest-port 20005 --home test-cluster-n3 --seed 127.0.0.1:7000 > node3.log 2>&1" -NoNewWindow

Write-Host "Waiting 15 seconds for Gossip and Raft to stabilize..."
Start-Sleep -Seconds 15

Write-Host "Uploading Artifact..."
java -jar aegis-cli\target\aegis.jar artifact upload aegis-demo-job\src\main\java\aegis-demo-job.jar --seed=http://127.0.0.1:20001 > upload.log 2>&1

$uploadOutput = Get-Content upload.log | Out-String
Write-Host $uploadOutput
$artifactId = ""
if ($uploadOutput -match "artifact:\s+([a-f0-9]{64})") {
    $artifactId = $matches[1]
} else {
    Write-Host "Failed to upload artifact. See upload.log."
    Stop-Process -Name java -Force -ErrorAction SilentlyContinue
    Exit 1
}

Write-Host "Submitting Process..."
java -jar aegis-cli\target\aegis.jar process submit --artifact $artifactId --cpu 1 --memory 128 --seed 127.0.0.1:20001 > submit.log 2>&1

$submitOutput = Get-Content submit.log | Out-String
Write-Host $submitOutput
$processId = ""
if ($submitOutput -match "([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})") {
    $processId = $matches[1]
} else {
    Write-Host "Failed to submit process. See submit.log."
    Stop-Process -Name java -Force -ErrorAction SilentlyContinue
    Exit 1
}
Write-Host "Process ID: $processId"

Start-Sleep -Seconds 3

Write-Host "Verifying Process is RUNNING..."
java -jar aegis-cli\target\aegis.jar process status $processId --seed 127.0.0.1:20001

Write-Host "Cancelling Process..."
java -jar aegis-cli\target\aegis.jar process cancel $processId --seed 127.0.0.1:20001

Start-Sleep -Seconds 2

Write-Host "Verifying Process is CANCELLED..."
java -jar aegis-cli\target\aegis.jar process status $processId --seed 127.0.0.1:20001

Write-Host "Checking Engine Logs for 'Forcefully terminated'..."
Get-Content node1.log, node2.log, node3.log | Select-String "Forcefully terminated process $processId"

Write-Host "Terminating nodes..."
Stop-Process -Name java -Force -ErrorAction SilentlyContinue
