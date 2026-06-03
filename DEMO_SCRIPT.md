# AegisOS -- 5-Minute Demo Video Script

> A complete guide to recording a professional demo of AegisOS v0.1.0-rc1.
> Total target runtime: **4:30 - 5:00 minutes**
> All commands below are VERIFIED to work end-to-end.

---

## Before You Start Recording

### Terminal Setup
- Use a dark theme terminal (Windows Terminal with One Dark or Dracula)
- Font size: **16-18px minimum** so text is readable in video
- Open **2 PowerShell tabs**:
  - Tab 1: your main command pane (where you type all commands)
  - Tab 2: live tail of node logs (optional, looks great on screen)

### Pre-Build (do this BEFORE hitting record)
```powershell
cd C:\Users\astra\Desktop\projects\AgeisOS

# Build everything once
mvn -pl aegis-cli -am package -DskipTests -q
mvn -pl aegis-test-cluster test-compile -q

# Set the classpath variable you will use throughout the demo
$CP = "aegis-cli\target\aegis.jar;aegis-test-cluster\target\test-classes"
```

> **Critical:** Nodes must be started with BOTH jars on the classpath.
> `aegis.jar` alone will cause `deserialization failed` when running jobs.

### Clean previous run
```powershell
Get-Job | Stop-Job -PassThru | Remove-Job -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force node1, node2, node3 -ErrorAction SilentlyContinue
```

---

## Scene-by-Scene Script

---

### Scene 1 -- Introduction (0:00 - 0:25)

**No typing. Talk over a static view of the project or architecture diagram.**

> "AegisOS is a distributed operating system I built from scratch in Java.
> It has its own consensus algorithm, distributed file system, job scheduler,
> and runtime -- all communicating over an encrypted peer-to-peer network.
> In the next 5 minutes: cluster formation, encrypted file storage,
> a compute job, worker death with automatic migration, and leader failover.
> Everything runs on real distributed code -- no mocks, no simulators."

---

### Scene 2 -- Start the Cluster (0:25 - 1:00)

**IMPORTANT: Use Start-Job, not Start-Process.**
`Start-Process` kills the node when the shell session's I/O context changes.
`Start-Job` keeps nodes alive independently.

```powershell
$CP = "aegis-cli\target\aegis.jar;aegis-test-cluster\target\test-classes"

# Node 1 -- seed node
$j1 = Start-Job -Name "node1" -ScriptBlock {
    param($cp, $wd); Set-Location $wd
    java -cp $cp com.aegisos.cli.AegisCLI start --port 9001 --home node1 2>&1
} -ArgumentList $CP, (Get-Location).Path

Start-Sleep -Seconds 1

# Node 2 -- joins via node1
$j2 = Start-Job -Name "node2" -ScriptBlock {
    param($cp, $wd); Set-Location $wd
    java -cp $cp com.aegisos.cli.AegisCLI start --port 9002 --home node2 --seed 127.0.0.1:9001 2>&1
} -ArgumentList $CP, (Get-Location).Path

# Node 3 -- joins via node1
$j3 = Start-Job -Name "node3" -ScriptBlock {
    param($cp, $wd); Set-Location $wd
    java -cp $cp com.aegisos.cli.AegisCLI start --port 9003 --home node3 --seed 127.0.0.1:9001 2>&1
} -ArgumentList $CP, (Get-Location).Path
```

> "I'm starting a 3-node cluster. Each node does a Noise protocol handshake,
> exchanges Ed25519 keys, and gossips membership to the others.
> Within a few seconds, one node wins a Raft election and becomes leader."

```powershell
# Wait for election (takes ~5-8 seconds)
Start-Sleep -Seconds 8

# Check health -- should show HTTP 200 UP
Invoke-RestMethod http://localhost:19001/health
```

**Expected output:**
```
status      : UP
leaderKnown : True
alivePeers  : 3
```

> "HTTP 200 UP. The cluster has a leader, 3 alive peers. Ready."

```powershell
# Full metrics
Invoke-RestMethod http://localhost:19001/metrics
```

**Expected output (abbreviated):**
```
nodeId     : ce21f6...
role       : LEADER
term       : 4
aliveNodes : 3
```

> "Each node exposes /health for scripts and /metrics for dashboards.
> Both are plain HTTP -- no extra tools needed."

**Metrics port convention:**
- Node on port 9001 --> metrics on **19001**
- Node on port 9002 --> metrics on **19002**
- Node on port 9003 --> metrics on **19003**

---

### Scene 3 -- Store and Retrieve a File (1:00 - 1:30)

```powershell
# Create a file
"Hello from AegisOS! This file survives node failures." | Set-Content demo.txt
cat demo.txt
```

```powershell
# Upload to the cluster
java -cp $CP com.aegisos.cli.AegisCLI put --seed 127.0.0.1:9001 demo.txt /demo.txt
```

> "The file is split into encrypted chunks.
> Each chunk is AES-256-GCM encrypted with a unique key.
> Chunks are replicated to 3 nodes via a Kademlia DHT.
> The file metadata is committed to the Raft log -- every node agrees it exists."

```powershell
# Retrieve it
java -cp $CP com.aegisos.cli.AegisCLI get --seed 127.0.0.1:9001 /demo.txt recovered.txt
cat recovered.txt
```

**Expected:** identical content to `demo.txt`

> "Retrieved. Same content. Durable across the cluster."

---

### Scene 4 -- Run a Compute Job (1:30 - 2:10)

```powershell
java -cp $CP com.aegisos.cli.AegisCLI run `
    --seed 127.0.0.1:9001 `
    com.aegisos.cluster.jobs.PrimeCounter `
    10000
```

> "I'm submitting a distributed compute job -- a prime counter up to 10,000.
> The scheduler checks node resource availability,
> commits ASSIGN_JOB to the Raft log to make the placement durable,
> then sends RUN_JOB to the selected node over the encrypted network.
> The job runs on a virtual thread. Result is committed back to the Raft log."

**Expected output:**
```
Result: 1229
```

> "1229 primes below 10,000. Correct.
> That computation ran on one of the cluster nodes, not here locally."

---

### Scene 5 -- Worker Death and Job Migration (2:10 - 3:30)

```powershell
# Submit a 25-second job in the background
$sleepJob = Start-Job -Name "sleepjob" -ScriptBlock {
    param($cp, $wd); Set-Location $wd
    java -cp $cp com.aegisos.cli.AegisCLI run `
        --seed 127.0.0.1:9001 `
        com.aegisos.cluster.jobs.SleepJob `
        25000 2>&1
} -ArgumentList $CP, (Get-Location).Path

Write-Host "SleepJob submitted (25 seconds)."
Start-Sleep -Seconds 3
```

```powershell
# See which node picked it up
Invoke-RestMethod http://localhost:19001/metrics | Select-Object role, @{n='running';e={$_.jobs.RUNNING}}
Invoke-RestMethod http://localhost:19002/metrics | Select-Object role, @{n='running';e={$_.jobs.RUNNING}}
Invoke-RestMethod http://localhost:19003/metrics | Select-Object role, @{n='running';e={$_.jobs.RUNNING}}
```

> "I can see from the metrics which node is running the job.
> Now I'll kill node 2."

```powershell
Stop-Job $j2; Remove-Job $j2 -Force
Write-Host "Node 2 killed."
```

> "Node 2 is dead. Watch what happens."

```powershell
# Watch migration happen (check the surviving nodes)
Start-Sleep -Seconds 8
Invoke-RestMethod http://localhost:19001/metrics | Select-Object aliveNodes, @{n='running';e={$_.jobs.RUNNING}}
Invoke-RestMethod http://localhost:19003/metrics | Select-Object aliveNodes, @{n='running';e={$_.jobs.RUNNING}}
```

> "The gossip protocol marked node 2 as DEAD.
> MigrationCoordinator detected the orphaned job,
> re-scheduled it on a surviving node with the latest checkpoint,
> all automatically -- no human intervention."

```powershell
# Wait for the sleep job to finish
$sleepJob | Wait-Job -Timeout 45 | Out-Null
Receive-Job $sleepJob | Select-Object -Last 3
```

**Expected:** `Result: true`

```powershell
# Verify the file is still readable after node loss
java -cp $CP com.aegisos.cli.AegisCLI get --seed 127.0.0.1:9001 /demo.txt check.txt
cat check.txt
```

> "File still intact. Raft-backed replication with self-healing."

---

### Scene 6 -- Leader Failover (3:30 - 4:20)

```powershell
# Find the current leader
foreach ($port in 19001, 19003) {
    $m = Invoke-RestMethod "http://localhost:$port/metrics" -ErrorAction SilentlyContinue
    if ($m) { Write-Host "Port $port : $($m.role)  term=$($m.term)" }
}
```

> "Now the hardest failure: what if the leader itself dies?"

```powershell
# Kill the leader (node1 in this example -- adjust if node3 is leader)
Stop-Job $j1; Remove-Job $j1 -Force
Write-Host "Leader killed."
```

> "Leader is gone. Surviving nodes detect the timeout and start a new election."

```powershell
# Watch for new leader (takes 2-5 seconds)
Start-Sleep -Seconds 8
Invoke-RestMethod http://localhost:19003/metrics
```

**Expected:**
```
role  : LEADER
term  : 8        # higher term than before
```

```powershell
# Prove cluster still serves requests
java -cp $CP com.aegisos.cli.AegisCLI get --seed 127.0.0.1:9003 /demo.txt final.txt
cat final.txt
```

> "New leader elected. Higher term. Same file -- no data loss.
> This is what Raft-backed replication actually guarantees."

---

### Scene 7 -- Closing (4:20 - 5:00)

**Show the /metrics endpoint one more time.**

```powershell
Invoke-RestMethod http://localhost:19003/metrics
```

> "AegisOS implements, from scratch:
>
> Noise protocol handshake and ChaCha20-Poly1305 encryption at the transport layer.
> Gossip-based membership with SUSPECT/DEAD eviction.
> Raft consensus: elections, log replication, persistent recovery.
> A DHT-placed, AES-256-GCM encrypted distributed filesystem.
> A resource-aware job scheduler with durable assignment via Raft.
> A migration runtime that survives worker death mid-execution.
>
> The full test suite -- Phase 1 through Phase 6 -- runs in under a minute.
> Source is on GitHub. Thanks for watching."

```powershell
# Cleanup
Get-Job | Stop-Job -PassThru | Remove-Job -ErrorAction SilentlyContinue
```

---

## Quick Reference -- All Commands

```powershell
# Set once at the start of every session
$CP = "aegis-cli\target\aegis.jar;aegis-test-cluster\target\test-classes"

# --- Start nodes (use Start-Job, NOT Start-Process) ---
$j1 = Start-Job -Name "node1" -ScriptBlock { param($cp,$wd); Set-Location $wd; java -cp $cp com.aegisos.cli.AegisCLI start --port 9001 --home node1 2>&1 } -ArgumentList $CP,(Get-Location).Path
Start-Sleep 1
$j2 = Start-Job -Name "node2" -ScriptBlock { param($cp,$wd); Set-Location $wd; java -cp $cp com.aegisos.cli.AegisCLI start --port 9002 --home node2 --seed 127.0.0.1:9001 2>&1 } -ArgumentList $CP,(Get-Location).Path
$j3 = Start-Job -Name "node3" -ScriptBlock { param($cp,$wd); Set-Location $wd; java -cp $cp com.aegisos.cli.AegisCLI start --port 9003 --home node3 --seed 127.0.0.1:9001 2>&1 } -ArgumentList $CP,(Get-Location).Path
Start-Sleep 8

# --- Health / Metrics ---
Invoke-RestMethod http://localhost:19001/health
Invoke-RestMethod http://localhost:19001/metrics
Invoke-RestMethod http://localhost:19002/metrics
Invoke-RestMethod http://localhost:19003/metrics

# --- File operations ---
java -cp $CP com.aegisos.cli.AegisCLI put --seed 127.0.0.1:9001 demo.txt /demo.txt
java -cp $CP com.aegisos.cli.AegisCLI get --seed 127.0.0.1:9001 /demo.txt recovered.txt

# --- Compute job: prime count to 10000 (result: 1229) ---
java -cp $CP com.aegisos.cli.AegisCLI run --seed 127.0.0.1:9001 com.aegisos.cluster.jobs.PrimeCounter 10000

# --- Long job: sleep 25 seconds ---
java -cp $CP com.aegisos.cli.AegisCLI run --seed 127.0.0.1:9001 com.aegisos.cluster.jobs.SleepJob 25000

# --- Kill a node ---
Stop-Job $j2; Remove-Job $j2 -Force   # kill node2
Stop-Job $j1; Remove-Job $j1 -Force   # kill node1 (if leader)

# --- Watch cluster state ---
foreach ($p in 19001,19002,19003) {
    $m = Invoke-RestMethod "http://localhost:$p/metrics" -ErrorAction SilentlyContinue
    if ($m) { Write-Host "Port $p | $($m.role) | alive=$($m.aliveNodes) | running=$($m.jobs.RUNNING)" }
}

# --- Full cleanup ---
Get-Job | Stop-Job -PassThru | Remove-Job -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force node1,node2,node3,demo.txt,recovered.txt,check.txt,final.txt -ErrorAction SilentlyContinue
```

---

## Timing Guide

| Scene | Content | Target Time |
|-------|---------|-------------|
| 1 | Intro | 0:00 - 0:25 |
| 2 | Start cluster, health + metrics | 0:25 - 1:00 |
| 3 | Store and retrieve file | 1:00 - 1:30 |
| 4 | PrimeCounter job (result: 1229) | 1:30 - 2:10 |
| 5 | Kill worker, job migrates | 2:10 - 3:30 |
| 6 | Kill leader, new election | 3:30 - 4:20 |
| 7 | Closing + metrics | 4:20 - 5:00 |

---

## Recording Tips

- **Use OBS Studio** (free) -- record at 1920x1080, 30fps
- **Practise the full run once** before recording to warm the JVM
- **Leave $CP set** across all terminal sessions -- it's easy to forget and hit `deserialization failed`
- **Slow your typing down** -- viewers need 2-3 extra seconds to read each result before you speak
- **Keep a second tab** tailing `Receive-Job $j1 -Keep | Select-Object -Last 5` -- the `became LEADER` log line is the best visual in the whole demo
- **Don't skip Scene 7** -- the /metrics JSON on screen while you summarise what's in it lands well

---

## What Makes Each Moment Impressive

| Scene | The standout moment |
|-------|---------------------|
| Cluster start | `/health` returning `{ "status": "UP" }` instantly |
| File store | Upload confirming "1 chunks" in under 1 second |
| Compute job | `Result: 1229` coming back from a different JVM |
| Migration | `running=1` appearing on a different node seconds after the kill |
| Leader failover | `role: LEADER  term: 8` on the surviving node |
| Closing | Live JSON metrics while you narrate |
