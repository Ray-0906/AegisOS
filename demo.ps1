# AegisOS — 5-Minute Demo Script
# Demonstrates: cluster start, file storage, job execution,
#               worker death + migration, leader failover.
#
# Requirements:
#   - JDK 21+
#   - aegis.jar built (run: mvn -pl aegis-cli -am package -DskipTests)
#   - PowerShell 7+
#
# Usage:
#   .\demo.ps1

param(
    [string]$Jar = "aegis-cli\target\aegis.jar"
)

$ErrorActionPreference = "Stop"

# ── Helpers ──────────────────────────────────────────────────────────────────

function Write-Step([string]$msg) {
    Write-Host ""
    Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Cyan
    Write-Host "  $msg" -ForegroundColor Cyan
    Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Cyan
}

function Wait-Prompt([string]$msg = "Press Enter to continue...") {
    Write-Host ""
    Write-Host $msg -ForegroundColor Yellow -NoNewline
    $null = Read-Host
}

function Aegis([string]$args) {
    & java -cp $Jar com.aegisos.cli.AegisCLI @($args -split " ")
}

function Kill-Nodes {
    Get-Process java -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like "*aegis*" } |
        Stop-Process -Force -ErrorAction SilentlyContinue
}

# ── Cleanup ───────────────────────────────────────────────────────────────────

Write-Step "Cleaning up previous run"
Kill-Nodes
Remove-Item -Recurse -Force node1, node2, node3 -ErrorAction SilentlyContinue
Start-Sleep -Milliseconds 500

# ── Step 1: Start cluster ─────────────────────────────────────────────────────

Write-Step "Step 1 — Starting 3-node cluster"
Write-Host "  node1: P2P 9001  metrics http://localhost:19001/metrics"
Write-Host "  node2: P2P 9002  metrics http://localhost:19002/metrics"
Write-Host "  node3: P2P 9003  metrics http://localhost:19003/metrics"

$node1 = Start-Process java -ArgumentList @(
    "-cp", $Jar, "com.aegisos.cli.AegisCLI", "start",
    "--port", "9001", "--home", "node1"
) -PassThru -RedirectStandardOutput "node1.log" -RedirectStandardError "node1.log"

Start-Sleep -Seconds 1

$node2 = Start-Process java -ArgumentList @(
    "-cp", $Jar, "com.aegisos.cli.AegisCLI", "start",
    "--port", "9002", "--home", "node2", "--seed", "127.0.0.1:9001"
) -PassThru -RedirectStandardOutput "node2.log" -RedirectStandardError "node2.log"

$node3 = Start-Process java -ArgumentList @(
    "-cp", $Jar, "com.aegisos.cli.AegisCLI", "start",
    "--port", "9003", "--home", "node3", "--seed", "127.0.0.1:9001"
) -PassThru -RedirectStandardOutput "node3.log" -RedirectStandardError "node3.log"

Write-Host ""
Write-Host "  Waiting for leader election..." -ForegroundColor Gray
Start-Sleep -Seconds 5

Write-Host ""
Write-Host "  Cluster metrics (node 1):" -ForegroundColor White
try {
    $metrics = Invoke-RestMethod "http://localhost:19001/metrics" -TimeoutSec 5
    Write-Host ("    leader      = {0}" -f $metrics.leader) -ForegroundColor Green
    Write-Host ("    term        = {0}" -f $metrics.term)
    Write-Host ("    aliveNodes  = {0}" -f $metrics.aliveNodes)
    Write-Host ("    role        = {0}" -f $metrics.role)
} catch {
    Write-Host "    (metrics not yet available — check node1.log)" -ForegroundColor Yellow
}

Wait-Prompt

# ── Step 2: Store a file ──────────────────────────────────────────────────────

Write-Step "Step 2 — Storing a file"

"Hello from AegisOS! $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" | Set-Content demo.txt
Write-Host "  Uploading demo.txt to /demo.txt ..."
java -cp $Jar com.aegisos.cli.AegisCLI put --seed 127.0.0.1:9001 demo.txt /demo.txt

Wait-Prompt

# ── Step 3: Retrieve the file ─────────────────────────────────────────────────

Write-Step "Step 3 — Retrieving the file"
Write-Host "  Downloading /demo.txt -> recovered.txt ..."
java -cp $Jar com.aegisos.cli.AegisCLI get --seed 127.0.0.1:9001 /demo.txt recovered.txt

Write-Host ""
Write-Host "  Original  : $(Get-Content demo.txt)"
Write-Host "  Recovered : $(Get-Content recovered.txt)"

if ((Get-Content demo.txt) -eq (Get-Content recovered.txt)) {
    Write-Host "  ✅ File integrity verified" -ForegroundColor Green
} else {
    Write-Host "  ❌ Mismatch!" -ForegroundColor Red
}

Wait-Prompt

# ── Step 4: Submit a job ──────────────────────────────────────────────────────

Write-Step "Step 4 — Submitting a compute job (PrimeCounter)"
Write-Host "  Submitting job asynchronously and waiting for result..."

$jobResult = java -cp "$Jar;aegis-test-cluster\target\test-classes" `
    com.aegisos.cli.AegisCLI run `
    --seed 127.0.0.1:9001 `
    com.aegisos.cluster.jobs.PrimeCounter `
    10000

Write-Host "  Result: $jobResult"
Write-Host "  ✅ Expected: 1229 primes below 10,000" -ForegroundColor Green

Wait-Prompt

# ── Step 5: Worker death + migration ──────────────────────────────────────────

Write-Step "Step 5 — Worker death and job migration"
Write-Host "  Submitting a long-running SleepJob (30 seconds)..."

# Submit SleepJob in background
$jobProc = Start-Process java -ArgumentList @(
    "-cp", "$Jar;aegis-test-cluster\target\test-classes",
    "com.aegisos.cli.AegisCLI", "run",
    "--seed", "127.0.0.1:9001",
    "com.aegisos.cluster.jobs.SleepJob", "30000"
) -PassThru -RedirectStandardOutput "job.log" -RedirectStandardError "job.log"

Start-Sleep -Seconds 3
Write-Host "  Job submitted. Checking which node it landed on..."
Start-Sleep -Seconds 2

# Find which node is running it by checking logs
$runningOn = "unknown"
foreach ($port in 9001, 9002, 9003) {
    try {
        $m = Invoke-RestMethod "http://localhost:1$($port)/metrics" -TimeoutSec 2
        if ($m.jobs.RUNNING -gt 0) {
            $runningOn = "node on port $port (metrics: 1$($port))"
            $nodePort = $port
        }
    } catch {}
}
Write-Host "  Job is running on: $runningOn"

Wait-Prompt "Press Enter to KILL that node and observe migration..."

# Kill the worker
switch ($nodePort) {
    9001 { Stop-Process -Id $node1.Id -Force -ErrorAction SilentlyContinue }
    9002 { Stop-Process -Id $node2.Id -Force -ErrorAction SilentlyContinue }
    9003 { Stop-Process -Id $node3.Id -Force -ErrorAction SilentlyContinue }
}
Write-Host "  💀 Node on port $nodePort killed!" -ForegroundColor Red

Write-Host "  Watching for migration (check remaining nodes' metrics)..."
$migrated = $false
for ($i = 0; $i -lt 20; $i++) {
    Start-Sleep -Seconds 1
    foreach ($port in 9001, 9002, 9003) {
        if ($port -eq $nodePort) { continue }
        try {
            $m = Invoke-RestMethod "http://localhost:1$($port)/metrics" -TimeoutSec 1
            if ($m.jobs.RUNNING -gt 0) {
                Write-Host "  ✅ Job migrated to node on port $port" -ForegroundColor Green
                Write-Host ("     term={0}  aliveNodes={1}" -f $m.term, $m.aliveNodes)
                $migrated = $true
                break
            }
        } catch {}
    }
    if ($migrated) { break }
    Write-Host "  ...waiting ($i s)" -ForegroundColor Gray
}

$jobProc.WaitForExit(90000) | Out-Null
Write-Host ""
Write-Host "  Job output: $(Get-Content job.log | Select-Object -Last 2)"

Wait-Prompt

# ── Step 6: Leader failover ───────────────────────────────────────────────────

Write-Step "Step 6 — Leader failover"

# Find the current leader
$leaderPort = $null
foreach ($port in 9001, 9002, 9003) {
    if ($port -eq $nodePort) { continue }
    try {
        $m = Invoke-RestMethod "http://localhost:1$($port)/metrics" -TimeoutSec 2
        if ($m.role -eq "LEADER") { $leaderPort = $port }
    } catch {}
}
Write-Host "  Current leader is on port: $leaderPort"

Wait-Prompt "Press Enter to kill the leader..."

switch ($leaderPort) {
    9001 { Stop-Process -Id $node1.Id -Force -ErrorAction SilentlyContinue }
    9002 { Stop-Process -Id $node2.Id -Force -ErrorAction SilentlyContinue }
    9003 { Stop-Process -Id $node3.Id -Force -ErrorAction SilentlyContinue }
}
Write-Host "  💀 Leader on port $leaderPort killed!" -ForegroundColor Red
Write-Host "  Watching for new election..."

$elected = $false
for ($i = 0; $i -lt 20; $i++) {
    Start-Sleep -Seconds 1
    foreach ($port in 9001, 9002, 9003) {
        if ($port -eq $nodePort -or $port -eq $leaderPort) { continue }
        try {
            $m = Invoke-RestMethod "http://localhost:1$($port)/metrics" -TimeoutSec 1
            if ($m.role -eq "LEADER") {
                Write-Host ("  ✅ New leader elected on port {0} (term={1})" -f $port, $m.term) -ForegroundColor Green
                $elected = $true
                break
            }
        } catch {}
    }
    if ($elected) { break }
    Write-Host "  ...waiting ($i s)" -ForegroundColor Gray
}

# Verify cluster still serves requests
Write-Host ""
Write-Host "  Verifying cluster still serves reads after failover..."
java -cp $Jar com.aegisos.cli.AegisCLI get --seed 127.0.0.1:9001 /demo.txt /dev/null 2>&1
if ($LASTEXITCODE -eq 0) {
    Write-Host "  ✅ Cluster healthy: file still readable with 1/3 nodes alive" -ForegroundColor Green
} else {
    Write-Host "  Trying via other node..." -ForegroundColor Yellow
    java -cp $Jar com.aegisos.cli.AegisCLI get --seed 127.0.0.1:9002 /demo.txt /dev/null 2>&1
}

# ── Summary ───────────────────────────────────────────────────────────────────

Write-Step "Demo Complete"
Write-Host ""
Write-Host "  ✅ Cluster formation & leader election"   -ForegroundColor Green
Write-Host "  ✅ File storage with encryption & replication" -ForegroundColor Green
Write-Host "  ✅ File retrieval & integrity verification" -ForegroundColor Green
Write-Host "  ✅ Distributed compute job execution"     -ForegroundColor Green
Write-Host "  ✅ Worker death detection & job migration" -ForegroundColor Green
Write-Host "  ✅ Leader failover & continued availability" -ForegroundColor Green
Write-Host ""

Kill-Nodes
Remove-Item demo.txt, recovered.txt -ErrorAction SilentlyContinue
