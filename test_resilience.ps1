$CP = "aegis-cli\target\aegis.jar;aegis-test-cluster\target\test-classes"

function Log($msg)  { Write-Host "`n>>> $msg" -ForegroundColor Cyan }
function Ok($msg)   { Write-Host "    PASS: $msg" -ForegroundColor Green }
function Fail($msg) { Write-Host "    FAIL: $msg" -ForegroundColor Red }
function Info($msg) { Write-Host "    $msg" -ForegroundColor White }

# ─── Start cluster ─────────────────────────────────────────────────────────
Log "Starting 3-node cluster (node1=SEED, node2, node3)..."

$j1 = Start-Job -Name "node1" -ScriptBlock {
    param($cp,$wd); Set-Location $wd
    java -cp $cp com.aegisos.cli.AegisCLI start --port 9001 --home node1 2>&1
} -ArgumentList $CP,(Get-Location).Path

Start-Sleep -Seconds 1

$j2 = Start-Job -Name "node2" -ScriptBlock {
    param($cp,$wd); Set-Location $wd
    java -cp $cp com.aegisos.cli.AegisCLI start --port 9002 --home node2 --seed 127.0.0.1:9001 2>&1
} -ArgumentList $CP,(Get-Location).Path

$j3 = Start-Job -Name "node3" -ScriptBlock {
    param($cp,$wd); Set-Location $wd
    java -cp $cp com.aegisos.cli.AegisCLI start --port 9003 --home node3 --seed 127.0.0.1:9001 2>&1
} -ArgumentList $CP,(Get-Location).Path

Start-Sleep -Seconds 8

$m1 = Invoke-RestMethod http://localhost:19001/metrics
$m2 = Invoke-RestMethod http://localhost:19002/metrics
$m3 = Invoke-RestMethod http://localhost:19003/metrics

Info "node1: role=$($m1.role)  alive=$($m1.aliveNodes)"
Info "node2: role=$($m2.role)  alive=$($m2.aliveNodes)"
Info "node3: role=$($m3.role)  alive=$($m3.aliveNodes)"

# Figure out which is leader and which is seed
$leaderPort  = @(19001,19002,19003) | Where-Object { (Invoke-RestMethod "http://localhost:$_/metrics").role -eq "LEADER" } | Select-Object -First 1
$leaderP2P   = $leaderPort - 10000
Info "Leader is on P2P port $leaderP2P (metrics $leaderPort)"

# ─── Upload a file ─────────────────────────────────────────────────────────
Log "Uploading test file to cluster..."
"This is the test file. Replication factor = 3." | Set-Content seed_test.txt
java -cp $CP com.aegisos.cli.AegisCLI put --seed 127.0.0.1:9001 seed_test.txt /seed_test.txt
Info "File uploaded to /seed_test.txt (replication factor 3 = copy on every node)"

# Show chunks on each node
Info "Chunk counts per node:"
Info "  node1/data/chunks: $((Get-ChildItem node1\data\chunks -ErrorAction SilentlyContinue | Measure-Object).Count) chunks"
Info "  node2/data/chunks: $((Get-ChildItem node2\data\chunks -ErrorAction SilentlyContinue | Measure-Object).Count) chunks"
Info "  node3/data/chunks: $((Get-ChildItem node3\data\chunks -ErrorAction SilentlyContinue | Measure-Object).Count) chunks"

# ═══════════════════════════════════════════════════════════════════════════
# EXPERIMENT 1: Kill the seed node (node1)
# ═══════════════════════════════════════════════════════════════════════════
Log "EXPERIMENT 1: Killing the SEED node (node1, port 9001)..."
Write-Host "    NOTE: node1 may also be the leader - we'll see!" -ForegroundColor Yellow

Stop-Job $j1; Remove-Job $j1 -Force
Info "node1 killed. Waiting 10s for gossip to detect failure + possible re-election..."
Start-Sleep -Seconds 10

# Check surviving nodes
$alive = @()
foreach ($port in 19002,19003) {
    try {
        $m = Invoke-RestMethod "http://localhost:$port/metrics" -TimeoutSec 3
        $alive += $port
        Info "node on port $($port-10000): role=$($m.role)  alive=$($m.aliveNodes)  term=$($m.term)  leaderKnown=$($m.leaderKnown)"
    } catch {
        Info "node on port $($port-10000): NOT RESPONDING"
    }
}

# Try to read file from surviving nodes
Log "Can we still READ the file after seed node dies?"
$readOk = $false
foreach ($port in 9002,9003) {
    Remove-Item recovered_exp1.txt -ErrorAction SilentlyContinue
    $out = java -cp $CP com.aegisos.cli.AegisCLI get --seed 127.0.0.1:$port /seed_test.txt recovered_exp1.txt 2>&1
    if ($LASTEXITCODE -eq 0 -and (Test-Path recovered_exp1.txt)) {
        $content = Get-Content recovered_exp1.txt
        Info "Read via port ${port}: '$content'"
        Ok "File readable via port $port after seed node death"
        $readOk = $true; break
    } else {
        Info "Port ${port} read result: $out"
    }
}
if (!$readOk) { Fail "File NOT readable after seed node death" }

# Try to WRITE a new file (requires Raft quorum)
Log "Can we still WRITE to the cluster after seed node dies?"
"New data written after seed death." | Set-Content post_death.txt
$out = java -cp $CP com.aegisos.cli.AegisCLI put --seed 127.0.0.1:9002 post_death.txt /post_death.txt 2>&1
if ($LASTEXITCODE -eq 0) {
    Ok "WRITE succeeded! Cluster still has quorum (2/3 nodes alive)"
} else {
    # Check if node1 was the leader - if so quorum may be lost momentarily
    Info "Write output: $out"
    # Try after giving election time
    Start-Sleep -Seconds 5
    $out2 = java -cp $CP com.aegisos.cli.AegisCLI put --seed 127.0.0.1:9003 post_death.txt /post_death.txt 2>&1
    if ($LASTEXITCODE -eq 0) { Ok "WRITE succeeded after re-election settled" }
    else { Fail "WRITE failed even after waiting: $out2" }
}

# ═══════════════════════════════════════════════════════════════════════════
# EXPERIMENT 2: Now kill a SECOND node (only 1 node left = no quorum)
# ═══════════════════════════════════════════════════════════════════════════
Log "EXPERIMENT 2: Killing a SECOND node (now only 1 of 3 nodes alive = no quorum)..."

Stop-Job $j2; Remove-Job $j2 -Force
Info "node2 killed. Waiting 5s..."
Start-Sleep -Seconds 5

# With only 1 node, Raft cannot achieve quorum (need 2/3)
Log "Can we READ with only 1 node alive (no Raft quorum)?"
Remove-Item recovered_exp2.txt -ErrorAction SilentlyContinue
$out = java -cp $CP com.aegisos.cli.AegisCLI get --seed 127.0.0.1:9003 /seed_test.txt recovered_exp2.txt 2>&1
if ($LASTEXITCODE -eq 0 -and (Test-Path recovered_exp2.txt)) {
    $content = Get-Content recovered_exp2.txt
    Info "Read result: '$content'"
    Ok "READ still works with 1 node (reads don't require quorum, just local data)"
} else {
    Info "Read output: $out"
    Fail "READ failed with 1 node alive"
}

Log "Can we WRITE with only 1 node alive (no Raft quorum)?"
"Write attempt with no quorum." | Set-Content no_quorum.txt
$out = java -cp $CP com.aegisos.cli.AegisCLI put --seed 127.0.0.1:9003 no_quorum.txt /no_quorum.txt 2>&1
if ($LASTEXITCODE -ne 0) {
    Info "Write output: $out"
    Ok "WRITE correctly REFUSED: no quorum (need 2/3, only 1 alive). This is correct Raft behaviour."
} else {
    Fail "WRITE unexpectedly succeeded - this would be a safety violation!"
}

# ═══════════════════════════════════════════════════════════════════════════
# Summary
# ═══════════════════════════════════════════════════════════════════════════
Write-Host ""
Write-Host "===============================" -ForegroundColor Cyan
Write-Host "  RESULTS SUMMARY" -ForegroundColor Cyan
Write-Host "===============================" -ForegroundColor Cyan
Write-Host "  Seed node death:  cluster survives if >= 2 nodes alive" -ForegroundColor White
Write-Host "  File after death: readable from any surviving node" -ForegroundColor White
Write-Host "  Writes after death: succeed if quorum (>=2/3) intact" -ForegroundColor White
Write-Host "  1 node left: reads ok, writes refused (correct!)" -ForegroundColor White
Write-Host ""

Get-Job | Stop-Job -PassThru | Remove-Job -ErrorAction SilentlyContinue
Remove-Item seed_test.txt,post_death.txt,no_quorum.txt,recovered_exp1.txt,recovered_exp2.txt -ErrorAction SilentlyContinue
