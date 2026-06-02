Write-Host "Killing lingering java processes..."
Stop-Process -Name "java" -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2

Write-Host "Cleaning up old data..."
if (Test-Path "node1") { Remove-Item -Recurse -Force "node1" }
if (Test-Path "node2") { Remove-Item -Recurse -Force "node2" }
if (Test-Path "node3") { Remove-Item -Recurse -Force "node3" }
if (Test-Path "job_execution_log.txt") { Remove-Item -Force "job_execution_log.txt" }

Write-Host "Starting Node 1 (Seed)..."
$node1 = Start-Process -FilePath "java" -ArgumentList "-cp", "aegis-cli/target/aegis.jar;aegis-test-cluster/target/test-classes", "com.aegisos.cli.AegisCLI", "start", "--home", "node1", "--port", "9001" -RedirectStandardOutput "node1.log" -RedirectStandardError "node1.err" -PassThru -NoNewWindow

Write-Host "Starting Node 2..."
$node2 = Start-Process -FilePath "java" -ArgumentList "-cp", "aegis-cli/target/aegis.jar;aegis-test-cluster/target/test-classes", "com.aegisos.cli.AegisCLI", "start", "--home", "node2", "--port", "9002", "--seed", "127.0.0.1:9001" -RedirectStandardOutput "node2.log" -RedirectStandardError "node2.err" -PassThru -NoNewWindow

Write-Host "Starting Node 3..."
$node3 = Start-Process -FilePath "java" -ArgumentList "-cp", "aegis-cli/target/aegis.jar;aegis-test-cluster/target/test-classes", "com.aegisos.cli.AegisCLI", "start", "--home", "node3", "--port", "9003", "--seed", "127.0.0.1:9001" -RedirectStandardOutput "node3.log" -RedirectStandardError "node3.err" -PassThru -NoNewWindow

Start-Sleep -Seconds 5

Write-Host "Finding leader..."
$leaderNode = ""
$leaderPid = $null
for ($i=0; $i -lt 15; $i++) {
    $log1 = Get-Content node1.log -Raw -ErrorAction SilentlyContinue
    if ($log1 -match "became LEADER") { $leaderNode = "node1"; $leaderPid = $node1; break }
    $log2 = Get-Content node2.log -Raw -ErrorAction SilentlyContinue
    if ($log2 -match "became LEADER") { $leaderNode = "node2"; $leaderPid = $node2; break }
    $log3 = Get-Content node3.log -Raw -ErrorAction SilentlyContinue
    if ($log3 -match "became LEADER") { $leaderNode = "node3"; $leaderPid = $node3; break }
    Start-Sleep -Seconds 1
}

Write-Host "Detected Leader: $leaderNode"

if ($leaderNode -eq "") {
    Write-Host "No leader elected!"
    Stop-Process -Name "java" -Force -ErrorAction SilentlyContinue
    exit 1
}

$seedPort = "9001"
if ($leaderNode -eq "node1") { $seedPort = "9002" }

$script = {
    param($seedPort)
    Set-Location "C:\Users\astra\Desktop\projects\AgeisOS"
    $out = java -cp "aegis-cli/target/aegis.jar;aegis-test-cluster/target/test-classes" com.aegisos.cli.AegisCLI run --seed 127.0.0.1:$seedPort com.aegisos.cluster.jobs.WriteOnceJob 2>&1
    $out | Out-File "job_cli_output.log"
}

Write-Host "Submitting WriteOnceJob..."
$job = Start-Job -ScriptBlock $script -ArgumentList $seedPort

# Give it just enough time to start submitting
Start-Sleep -Milliseconds 150

Write-Host "Killing the leader ($leaderNode) during submission..."
Stop-Process -Id $leaderPid.Id -Force

Write-Host "Waiting up to 30s for the job to complete..."
Wait-Job $job -Timeout 30 | Out-Null
Receive-Job -Job $job | Out-Null

Write-Host "Job CLI Output:"
Get-Content job_cli_output.log -ErrorAction SilentlyContinue | Write-Host

Write-Host "Checking job_execution_log.txt..."
if (Test-Path "job_execution_log.txt") {
    $executions = (Get-Content "job_execution_log.txt").Count
    Write-Host "Job executed $executions times."
    Get-Content "job_execution_log.txt" | Write-Host
} else {
    Write-Host "Job did not execute (log file missing)."
}

Write-Host "Cleaning up remaining nodes..."
Stop-Process -Name "java" -Force -ErrorAction SilentlyContinue
Write-Host "Test completed."
