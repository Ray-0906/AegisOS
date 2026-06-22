$ErrorActionPreference = "SilentlyContinue"
Write-Host "Killing lingering java processes..."
Stop-Process -Name "java" -Force
Start-Sleep -Seconds 2

Write-Host "Cleaning up old data..."
if (Test-Path "node1") { Remove-Item -Recurse -Force "node1" }
if (Test-Path "node2") { Remove-Item -Recurse -Force "node2" }
if (Test-Path "node3") { Remove-Item -Recurse -Force "node3" }
if (Test-Path "job.log") { Remove-Item -Force "job.log" }

Write-Host "Starting Node 1 (Seed)..."
$node1 = Start-Process -FilePath "java" -ArgumentList "-cp", "aegis-cli/target/aegis.jar;aegis-test-cluster/target/test-classes", "com.aegisos.cli.AegisCLI", "start", "--home", "node1", "--port", "9001" -RedirectStandardOutput "node1.log" -RedirectStandardError "node1.err" -PassThru -NoNewWindow
Start-Sleep -Seconds 2

Write-Host "Starting Node 2..."
$node2 = Start-Process -FilePath "java" -ArgumentList "-cp", "aegis-cli/target/aegis.jar;aegis-test-cluster/target/test-classes", "com.aegisos.cli.AegisCLI", "start", "--home", "node2", "--port", "9002", "--seed", "127.0.0.1:9001" -RedirectStandardOutput "node2.log" -RedirectStandardError "node2.err" -PassThru -NoNewWindow

Write-Host "Starting Node 3..."
$node3 = Start-Process -FilePath "java" -ArgumentList "-cp", "aegis-cli/target/aegis.jar;aegis-test-cluster/target/test-classes", "com.aegisos.cli.AegisCLI", "start", "--home", "node3", "--port", "9003", "--seed", "127.0.0.1:9001" -RedirectStandardOutput "node3.log" -RedirectStandardError "node3.err" -PassThru -NoNewWindow
Start-Sleep -Seconds 5

Write-Host "Finding leader..."
for ($i=0; $i -lt 15; $i++) {
    $log1 = Get-Content node1.log -Raw -ErrorAction SilentlyContinue
    if ($log1 -match "became LEADER") { break }
    $log2 = Get-Content node2.log -Raw -ErrorAction SilentlyContinue
    if ($log2 -match "became LEADER") { break }
    $log3 = Get-Content node3.log -Raw -ErrorAction SilentlyContinue
    if ($log3 -match "became LEADER") { break }
    Start-Sleep -Seconds 1
}

$nodeMap = @{}

$n1log = Get-Content node1.log -Raw
if ($n1log -match "Starting node NodeId\(([\w]+)\)") { $nodeMap[$matches[1]] = "node1"; $pidMap = @{$matches[1] = $node1} }

$n2log = Get-Content node2.log -Raw
if ($n2log -match "Starting node NodeId\(([\w]+)\)") { $nodeMap[$matches[1]] = "node2"; $pidMap[$matches[1]] = $node2 }

$n3log = Get-Content node3.log -Raw
if ($n3log -match "Starting node NodeId\(([\w]+)\)") { $nodeMap[$matches[1]] = "node3"; $pidMap[$matches[1]] = $node3 }

Write-Host "Node map:"
$nodeMap | Out-String | Write-Host

$script = {
    Set-Location "C:\Users\astra\Desktop\projects\AgeisOS"
    # Run sleep for 30s
    cmd.exe /c "java -cp aegis-cli/target/aegis.jar;aegis-test-cluster/target/test-classes com.aegisos.cli.AegisCLI run --seed 127.0.0.1:9001 com.aegisos.cluster.jobs.SleepJob 30000 > job.log 2>&1"
}

Write-Host "Submitting SleepJob asynchronously..."
$job = Start-Job -ScriptBlock $script

Write-Host "Waiting for job to be scheduled..."
$targetNodeId = ""
for ($i=0; $i -lt 30; $i++) {
    if (Test-Path "job.log") {
        $content = Get-Content "job.log" -Raw
        if ($content -match "Submitted job ([\w-]+) to ([\w]+)") {
            $targetNodeId = $matches[2]
            break
        }
    }
    Start-Sleep -Seconds 1
}

if ($targetNodeId -eq "") {
    Write-Host "Failed to find scheduling log!"
    Stop-Process -Name "java" -Force
    exit 1
}

$workerName = $nodeMap[$targetNodeId]
$workerPid = $pidMap[$targetNodeId]
Write-Host "Job was scheduled on $workerName ($targetNodeId). Killing it..."

Stop-Process -Id $workerPid.Id -Force

Write-Host "Worker killed. Waiting to observe what happens to the job (waiting 60s)..."
Wait-Job $job -Timeout 60 | Out-Null
Receive-Job -Job $job | Out-Null

Write-Host "Job CLI Output:"
Get-Content job.log -ErrorAction SilentlyContinue | Write-Host

Write-Host "Cleaning up remaining nodes..."
Stop-Process -Name "java" -Force
Write-Host "Test completed."
