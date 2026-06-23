$ErrorActionPreference = "Stop"

Write-Host "Cleaning up old data..."
if (Test-Path test-cluster-n1) { Remove-Item -Recurse -Force test-cluster-n1 }
if (Test-Path test-cluster-n2) { Remove-Item -Recurse -Force test-cluster-n2 }
if (Test-Path test-cluster-n3) { Remove-Item -Recurse -Force test-cluster-n3 }
Stop-Process -Name java -Force -ErrorAction SilentlyContinue

Write-Host "Starting Node 1 (Bootstrap)..."
$node1 = Start-Process -FilePath "java" -ArgumentList "-jar aegis-cli\target\aegis.jar start --port 7000 --metrics-port 20000 --rest-port 20001 --home test-cluster-n1 --bootstrap" -RedirectStandardOutput "node1.out" -RedirectStandardError "node1.err" -NoNewWindow -PassThru

Start-Sleep -Seconds 5

Write-Host "Starting Node 2 (Peer)..."
$node2 = Start-Process -FilePath "java" -ArgumentList "-jar aegis-cli\target\aegis.jar start --port 7002 --metrics-port 20002 --rest-port 20003 --home test-cluster-n2 --seed 127.0.0.1:7000" -RedirectStandardOutput "node2.out" -RedirectStandardError "node2.err" -NoNewWindow -PassThru

Write-Host "Starting Node 3 (Peer)..."
$node3 = Start-Process -FilePath "java" -ArgumentList "-jar aegis-cli\target\aegis.jar start --port 7004 --metrics-port 20004 --rest-port 20005 --home test-cluster-n3 --seed 127.0.0.1:7000" -RedirectStandardOutput "node3.out" -RedirectStandardError "node3.err" -NoNewWindow -PassThru

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

Write-Host "Submitting 15 Processes..."
for ($i = 1; $i -le 15; $i++) {
    java -jar aegis-cli\target\aegis.jar process submit --artifact $artifactId --cpu 1 --memory 128 --seed 127.0.0.1:20001 | Out-Null
}

Start-Sleep -Seconds 3

Write-Host "--- Process List Before Kill ---"
java -jar aegis-cli\target\aegis.jar process list --seed 127.0.0.1:20001 | findstr /v "Discovered leader INFO"

Write-Host "Killing Node 3 aggressively..."
$node3.Kill()

Write-Host "Waiting 20 seconds for Gossip to timeout Node 3 to DEAD and Reconciler to act..."
Start-Sleep -Seconds 20

Write-Host "--- Process List After Reconciler Sweep ---"
java -jar aegis-cli\target\aegis.jar process list --seed 127.0.0.1:20001 | findstr /v "Discovered leader INFO"

Write-Host "Checking Engine Logs for 'Emitting FAILED'..."
Get-Content node1.out, node3.out | Select-String "Emitting FAILED"

Write-Host "Terminating nodes..."
Stop-Process -Name java -Force -ErrorAction SilentlyContinue
