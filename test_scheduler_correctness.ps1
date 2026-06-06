$ErrorActionPreference = "Stop"

Write-Host "Killing lingering java processes..."
try { taskkill /F /IM java.exe 2>$null } catch {}

Write-Host "Cleaning up old data..."
rm -Recurse -Force data -ErrorAction SilentlyContinue
mkdir data | Out-Null
mkdir data/job_results | Out-Null

$env:CP="aegis-cli/target/aegis.jar;aegis-test-cluster/target/test-classes"

Write-Host "Starting cluster..."
$p1 = Start-Process java -ArgumentList "-cp `"$env:CP`" com.aegisos.cli.AegisCLI start --home data/node1 --port 9001" -RedirectStandardOutput data/node1.log -RedirectStandardError data/node1_err.log -WindowStyle Hidden -PassThru
$p2 = Start-Process java -ArgumentList "-cp `"$env:CP`" com.aegisos.cli.AegisCLI start --home data/node2 --port 9002 --seed 127.0.0.1:9001" -RedirectStandardOutput data/node2.log -RedirectStandardError data/node2_err.log -WindowStyle Hidden -PassThru
$p3 = Start-Process java -ArgumentList "-cp `"$env:CP`" com.aegisos.cli.AegisCLI start --home data/node3 --port 9003 --seed 127.0.0.1:9001" -RedirectStandardOutput data/node3.log -RedirectStandardError data/node3_err.log -WindowStyle Hidden -PassThru

Start-Sleep -Seconds 5

Write-Host "Submitting 150 jobs asynchronously to test load balancing and capacity..."
$procs = @()
for ($i=1; $i -le 150; $i++) {
    $proc = Start-Process java -ArgumentList "-cp `"$env:CP`" com.aegisos.cli.AegisCLI run --seed 127.0.0.1:9001 com.aegisos.cluster.jobs.PrimeCounter" -RedirectStandardOutput "data/job_results/job_${i}.log" -RedirectStandardError "data/job_results/job_${i}_err.log" -WindowStyle Hidden -PassThru
    $procs += $proc
    Start-Sleep -Milliseconds 20
}

Write-Host "Waiting for all jobs to complete..."
foreach ($p in $procs) {
    $p.WaitForExit(300000) | Out-Null
}

Write-Host "Parsing results from nodes to count assigned jobs..."
$n1Jobs = 0
$n2Jobs = 0
$n3Jobs = 0

if (Test-Path data/node1.log) { $n1Jobs = @(Get-Content data/node1.log | Select-String "COMPLETED").Count }
if (Test-Path data/node2.log) { $n2Jobs = @(Get-Content data/node2.log | Select-String "COMPLETED").Count }
if (Test-Path data/node3.log) { $n3Jobs = @(Get-Content data/node3.log | Select-String "COMPLETED").Count }

Write-Host "Jobs completed on Node 1: $n1Jobs"
Write-Host "Jobs completed on Node 2: $n2Jobs"
Write-Host "Jobs completed on Node 3: $n3Jobs"

$total = $n1Jobs + $n2Jobs + $n3Jobs
Write-Host "Total jobs scheduled: $total"

if ($total -lt 140) {
    Write-Host "FAILURE: Not enough jobs were scheduled (expected >= 140, got $total)!"
    exit 1
}

if ($n1Jobs -gt 100 -or $n2Jobs -gt 100 -or $n3Jobs -gt 100) {
    Write-Host "FAILURE: A node exceeded its max capacity of 100 jobs! Overload protection failed."
    exit 1
}

# Load balancing check - each node should have roughly equal jobs
if ($n1Jobs -lt 30 -or $n2Jobs -lt 30 -or $n3Jobs -lt 30) {
    Write-Host "FAILURE: Load balancing is extremely skewed!"
    exit 1
}

Write-Host "SUCCESS: Scheduler correctness verified. Nodes respected capacity and load balanced jobs."

Stop-Process -Id $p1.Id -Force -ErrorAction SilentlyContinue
Stop-Process -Id $p2.Id -Force -ErrorAction SilentlyContinue
Stop-Process -Id $p3.Id -Force -ErrorAction SilentlyContinue
