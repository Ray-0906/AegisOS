$ErrorActionPreference = "Stop"

Write-Host "Killing lingering java processes..."
try { taskkill /F /IM java.exe 2>$null } catch {}

Write-Host "Cleaning up old data..."
if (Test-Path "node1") { Remove-Item -Recurse -Force "node1" }
if (Test-Path "node2") { Remove-Item -Recurse -Force "node2" }
if (Test-Path "node3") { Remove-Item -Recurse -Force "node3" }
if (Test-Path "job_results") { Remove-Item -Recurse -Force "job_results" }
New-Item -ItemType Directory -Force -Path "job_results" | Out-Null

Write-Host "Starting Node 1 (Seed)..."
$node1 = Start-Process -FilePath "java" -ArgumentList "-jar", "aegis-cli/target/aegis.jar", "start", "--home", "node1", "--port", "9001" -RedirectStandardOutput "node1_out.log" -RedirectStandardError "node1_err.log" -PassThru -NoNewWindow
Start-Sleep -Seconds 2

Write-Host "Starting Node 2..."
$node2 = Start-Process -FilePath "java" -ArgumentList "-jar", "aegis-cli/target/aegis.jar", "start", "--home", "node2", "--port", "9002", "--seed", "127.0.0.1:9001" -RedirectStandardOutput "node2_out.log" -RedirectStandardError "node2_err.log" -PassThru -NoNewWindow
Start-Sleep -Seconds 2

Write-Host "Starting Node 3..."
$node3 = Start-Process -FilePath "java" -ArgumentList "-jar", "aegis-cli/target/aegis.jar", "start", "--home", "node3", "--port", "9003", "--seed", "127.0.0.1:9001" -RedirectStandardOutput "node3_out.log" -RedirectStandardError "node3_err.log" -PassThru -NoNewWindow
Start-Sleep -Seconds 8

Write-Host "Finding leader..."
$leaderNode = ""
$log1 = Get-Content "node1_out.log" -Tail 50 | Select-String "became LEADER"
$log2 = Get-Content "node2_out.log" -Tail 50 | Select-String "became LEADER"
$log3 = Get-Content "node3_out.log" -Tail 50 | Select-String "became LEADER"

if ($log1) { $leaderNode = "node1"; $leaderProc = $node1 }
elseif ($log2) { $leaderNode = "node2"; $leaderProc = $node2 }
elseif ($log3) { $leaderNode = "node3"; $leaderProc = $node3 }

Write-Host "Detected Leader: $leaderNode"

Write-Host "Submitting 50 PrimeCounter jobs asynchronously (100ms delay)..."
$jobs = @()
for ($i=1; $i -le 50; $i++) {
    $script = {
        param($idx, $seedPort)
        Set-Location "C:\Users\astra\Desktop\projects\AgeisOS"
        $out = java -cp "aegis-cli/target/aegis.jar;aegis-test-cluster/target/test-classes" com.aegisos.cli.AegisCLI run --seed 127.0.0.1:$seedPort com.aegisos.cluster.jobs.PrimeCounter 2>&1
        $out | Out-File "job_results/job_${idx}.log"
    }
    $seedPort = "9001"
    if ($leaderNode -eq "node1") { $seedPort = "9002" }

    $job = Start-Job -ScriptBlock $script -ArgumentList $i, $seedPort
    $jobs += $job
    
    Start-Sleep -Milliseconds 100
    
    if ($i -eq 25) {
        Write-Host "Killing the leader ($leaderNode) during submissions..."
        if ($leaderProc) {
            Stop-Process -Id $leaderProc.Id -Force
        }
        Write-Host "Waiting 5 seconds before resuming submissions..."
        Start-Sleep -Seconds 5
    }
}

Write-Host "Waiting for all jobs to complete..."
Wait-Job -Job $jobs -Timeout 120 | Out-Null
Receive-Job -Job $jobs | Out-Null

Write-Host "Parsing results..."
$completed = 0
$failed = 0
$notLeaderExceptions = 0
$retries = 0
$lost = 0

for ($i=1; $i -le 50; $i++) {
    if (-not (Test-Path "job_results/job_${i}.log")) {
        $lost++
        continue
    }

    $content = Get-Content "job_results/job_${i}.log" -Raw -ErrorAction SilentlyContinue
    if ($content -match "Result:") {
        $completed++
    } else {
        $failed++
    }
    
    if ($content -match "NotLeaderException") {
        $notLeaderExceptions++
    }
    if ($content -match "Retrying schedule") {
        $retries++
    }
}

Write-Host "Jobs Submitted: 50"
Write-Host "Jobs Completed: $completed"
Write-Host "Jobs Failed: $failed"
Write-Host "Jobs Lost (no log): $lost"
Write-Host "NotLeaderExceptions Encountered: $notLeaderExceptions"
Write-Host "Retries Triggered: $retries"

Write-Host "Cleaning up remaining nodes..."
if ($leaderNode -ne "node1") { Stop-Process -Id $node1.Id -Force -ErrorAction SilentlyContinue }
if ($leaderNode -ne "node2") { Stop-Process -Id $node2.Id -Force -ErrorAction SilentlyContinue }
if ($leaderNode -ne "node3") { Stop-Process -Id $node3.Id -Force -ErrorAction SilentlyContinue }

Write-Host "Test completed."
