Write-Host "Killing lingering java processes..."
Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force

Write-Host "Cleaning up old data..."
rm -Recurse -Force data -ErrorAction SilentlyContinue
mkdir data -ErrorAction SilentlyContinue | Out-Null
mkdir data/job_results -ErrorAction SilentlyContinue | Out-Null

# Build the classpath
$cp="aegis-cli/target/aegis.jar;aegis-test-cluster/target/test-classes"
$env:CP = $cp

Write-Host "Starting cluster..."
$p1 = Start-Process java -ArgumentList "-cp `"$cp`" com.aegisos.cli.AegisCLI start --home data/node1 --port 9001" -RedirectStandardOutput "data/node1.log" -WindowStyle Hidden -PassThru
$p2 = Start-Process java -ArgumentList "-cp `"$cp`" com.aegisos.cli.AegisCLI start --home data/node2 --port 9002 --seed 127.0.0.1:9001" -RedirectStandardOutput "data/node2.log" -WindowStyle Hidden -PassThru
$p3 = Start-Process java -ArgumentList "-cp `"$cp`" com.aegisos.cli.AegisCLI start --home data/node3 --port 9003 --seed 127.0.0.1:9001" -RedirectStandardOutput "data/node3.log" -WindowStyle Hidden -PassThru

Start-Sleep -Seconds 5

Write-Host "Submitting a long-running job to the cluster..."
$jobFile = "data/job_result.log"
$jobProc = Start-Process java -ArgumentList "-cp `"$cp`" com.aegisos.cli.AegisCLI run --seed 127.0.0.1:9001 com.aegisos.cluster.jobs.SleepJob" -RedirectStandardOutput $jobFile -WindowStyle Hidden -PassThru

Start-Sleep -Seconds 5

Write-Host "Identifying current leader and assigned worker..."
$leaderProc = $null
$workerProc = $null

if ((Get-Content data/node1.log -ErrorAction SilentlyContinue) -match "became LEADER") { $leaderProc = $p1 }
elseif ((Get-Content data/node2.log -ErrorAction SilentlyContinue) -match "became LEADER") { $leaderProc = $p2 }
elseif ((Get-Content data/node3.log -ErrorAction SilentlyContinue) -match "became LEADER") { $leaderProc = $p3 }

if ($leaderProc -eq $null) {
    Write-Host "FAILURE: No leader elected."
    exit 1
}

Write-Host "Killing the Leader to test heartbeat failover..."
Stop-Process -Id $leaderProc.Id -Force

Start-Sleep -Seconds 10

Write-Host "Waiting for job to complete..."
$jobProc.WaitForExit(60000)

$res = Get-Content $jobFile -ErrorAction SilentlyContinue
if ($res -match "Result: true") {
    Write-Host "Job completed successfully."
} else {
    Write-Host "FAILURE: Job did not complete successfully. Result: $res"
    exit 1
}

Write-Host "Verifying no duplicate recovery occurred..."
$fencingCount = 0
if (Test-Path data/node1.log) { $fencingCount += @(Get-Content data/node1.log | Select-String "Fencing rejected").Count }
if (Test-Path data/node2.log) { $fencingCount += @(Get-Content data/node2.log | Select-String "Fencing rejected").Count }
if (Test-Path data/node3.log) { $fencingCount += @(Get-Content data/node3.log | Select-String "Fencing rejected").Count }

if ($fencingCount -gt 0) {
    Write-Host "FAILURE: Fencing rejections occurred, meaning job was duplicated during failover!"
    exit 1
}

$lostCount = 0
if (Test-Path data/node1.log) { $lostCount += @(Get-Content data/node1.log | Select-String "LOST").Count }
if (Test-Path data/node2.log) { $lostCount += @(Get-Content data/node2.log | Select-String "LOST").Count }
if (Test-Path data/node3.log) { $lostCount += @(Get-Content data/node3.log | Select-String "LOST").Count }

if ($lostCount -gt 0) {
    Write-Host "FAILURE: Job was marked LOST, meaning recovery was incorrectly triggered!"
    exit 1
}

Write-Host "SUCCESS: Heartbeat leader failover verified. New leader did not incorrectly trigger recovery."

Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force
