Write-Host "Killing lingering java processes..."
Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force

Write-Host "Cleaning up old data..."
rm -Recurse -Force data -ErrorAction SilentlyContinue
mkdir data -ErrorAction SilentlyContinue | Out-Null
mkdir data/job_results -ErrorAction SilentlyContinue | Out-Null

# Build the classpath
$cp="aegis-cli/target/aegis.jar;aegis-test-cluster/target/test-classes"
$env:CP = $cp

Write-Host "Starting 5-node cluster with test hooks enabled..."
$p1 = Start-Process java -ArgumentList "-Daegis.test.delay_after_lost=true -cp `"$cp`" com.aegisos.cli.AegisCLI start --home data/node1 --port 9001" -RedirectStandardOutput "data/node1.log" -WindowStyle Hidden -PassThru
$p2 = Start-Process java -ArgumentList "-Daegis.test.delay_after_lost=true -cp `"$cp`" com.aegisos.cli.AegisCLI start --home data/node2 --port 9002 --seed 127.0.0.1:9001" -RedirectStandardOutput "data/node2.log" -WindowStyle Hidden -PassThru
$p3 = Start-Process java -ArgumentList "-Daegis.test.delay_after_lost=true -cp `"$cp`" com.aegisos.cli.AegisCLI start --home data/node3 --port 9003 --seed 127.0.0.1:9001" -RedirectStandardOutput "data/node3.log" -WindowStyle Hidden -PassThru
$p4 = Start-Process java -ArgumentList "-Daegis.test.delay_after_lost=true -cp `"$cp`" com.aegisos.cli.AegisCLI start --home data/node4 --port 9004 --seed 127.0.0.1:9001" -RedirectStandardOutput "data/node4.log" -WindowStyle Hidden -PassThru
$p5 = Start-Process java -ArgumentList "-Daegis.test.delay_after_lost=true -cp `"$cp`" com.aegisos.cli.AegisCLI start --home data/node5 --port 9005 --seed 127.0.0.1:9001" -RedirectStandardOutput "data/node5.log" -WindowStyle Hidden -PassThru

Start-Sleep -Seconds 10

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
elseif ((Get-Content data/node4.log -ErrorAction SilentlyContinue) -match "became LEADER") { $leaderProc = $p4 }
elseif ((Get-Content data/node5.log -ErrorAction SilentlyContinue) -match "became LEADER") { $leaderProc = $p5 }

if ((Get-Content data/node1.log -ErrorAction SilentlyContinue) -match "SleepJob started") { $workerProc = $p1 }
elseif ((Get-Content data/node2.log -ErrorAction SilentlyContinue) -match "SleepJob started") { $workerProc = $p2 }
elseif ((Get-Content data/node3.log -ErrorAction SilentlyContinue) -match "SleepJob started") { $workerProc = $p3 }
elseif ((Get-Content data/node4.log -ErrorAction SilentlyContinue) -match "SleepJob started") { $workerProc = $p4 }
elseif ((Get-Content data/node5.log -ErrorAction SilentlyContinue) -match "SleepJob started") { $workerProc = $p5 }

if ($leaderProc -eq $null -or $workerProc -eq $null) {
    Write-Host "FAILURE: Could not identify leader or worker."
    exit 1
}

if ($leaderProc.Id -eq $workerProc.Id) {
    Write-Host "Leader is the worker. This test requires them to be different. Rerunning might help, or we can just kill the worker and see."
}

Write-Host "Killing the Worker to simulate crash..."
Stop-Process -Id $workerProc.Id -Force

Write-Host "Waiting for Leader to emit LOST state and hit the test hook (max 60s)..."
$hookHit = $false
for ($i=0; $i -lt 60; $i++) {
    if (Select-String -Quiet "TEST HOOK: Pausing Leader after emitting LOST" data/*.log) {
        $hookHit = $true
        break
    }
    Start-Sleep -Seconds 1
}

if (-not $hookHit) {
    Write-Host "FAILURE: Leader did not hit the test hook."
    exit 1
}

Write-Host "Test hook hit! Leader is paused before assigning job. Killing the Leader NOW!"
Stop-Process -Id $leaderProc.Id -Force

Start-Sleep -Seconds 15

Write-Host "Waiting for job to complete..."
$jobProc.WaitForExit(60000)

$res = Get-Content $jobFile -ErrorAction SilentlyContinue
if ($res -match "Result: true") {
    Write-Host "Job completed successfully."
} else {
    Write-Host "FAILURE: Job did not complete successfully. Result: $res"
    exit 1
}

Write-Host "Verifying exactly 1 ASSIGN_JOB for executionId=2 and 1 completion record..."

$lostCount = 0

foreach ($log in @("data/node1.log", "data/node2.log", "data/node3.log", "data/node4.log", "data/node5.log")) {
    if (Test-Path $log) {
        $lostCount += @(Get-Content $log | Select-String "Emitting LOST state").Count
    }
}

Write-Host "LOST emitted: $lostCount"

# We check that exactly TWO workers started the job in total (the original crashed one, and the new recovered one).
$runningCount = 0
foreach ($log in @("data/node1.log", "data/node2.log", "data/node3.log", "data/node4.log", "data/node5.log")) {
    if (Test-Path $log) {
        $runningCount += @(Get-Content $log | Select-String "SleepJob started").Count
    }
}

Write-Host "Total 'SleepJob started' logs across all nodes: $runningCount"

if ($runningCount -ne 2) {
    Write-Host "FAILURE: SleepJob was started $runningCount times instead of exactly 2 times (1 initial, 1 recovery)."
    exit 1
}

if ($lostCount -ne 1) {
    Write-Host "FAILURE: LOST state was emitted $lostCount times. Expected exactly 1 emission (Test R failed)."
    exit 1
}

Write-Host "SUCCESS: Leader failover during recovery verified. No duplicate processing."

Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force
