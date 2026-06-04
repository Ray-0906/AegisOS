$ErrorActionPreference = "Stop"

$env:CP="aegis-cli/target/aegis.jar"

function Run-Aegis {
    param([string]$ArgsStr)
    Invoke-Expression "java -cp `"$env:CP`" com.aegisos.cli.AegisCLI $ArgsStr"
}

Write-Host "Cleaning up old data..."
rm -Recurse -Force data -ErrorAction SilentlyContinue
mkdir data | Out-Null

Write-Host "Starting cluster..."
$p1 = Start-Process java -ArgumentList "-cp `"$env:CP`" com.aegisos.cli.AegisCLI start --home data/node1 --port 9001" -RedirectStandardOutput data/node1.log -RedirectStandardError data/node1_err.log -WindowStyle Hidden -PassThru
$p2 = Start-Process java -ArgumentList "-cp `"$env:CP`" com.aegisos.cli.AegisCLI start --home data/node2 --port 9002 --seed 127.0.0.1:9001" -RedirectStandardOutput data/node2.log -RedirectStandardError data/node2_err.log -WindowStyle Hidden -PassThru
$p3 = Start-Process java -ArgumentList "-cp `"$env:CP`" com.aegisos.cli.AegisCLI start --home data/node3 --port 9003 --seed 127.0.0.1:9001" -RedirectStandardOutput data/node3.log -RedirectStandardError data/node3_err.log -WindowStyle Hidden -PassThru

Start-Sleep -Seconds 5
Write-Host "Nodes:"
Run-Aegis "nodes --seed 127.0.0.1:9001"

Write-Host "Uploading artifact..."
$uploadOut = Run-Aegis "artifact upload ../aegis-demo-job/target/aegis-demo-job-1.0.jar --seed 127.0.0.1:9001"
Write-Host $uploadOut

$uploadOutStr = $uploadOut -join " "
$artifactId = ""
if ($uploadOutStr -match "artifact:\s*([a-f0-9]+)") {
    $artifactId = $matches[1]
} else {
    Write-Host "Failed to extract artifact ID"
    exit 1
}
Write-Host "Artifact ID: $artifactId"

Write-Host "Submitting LongRunningJob in background..."
$jobProc = Start-Process java -ArgumentList "-cp `"$env:CP`" com.aegisos.cli.AegisCLI run --seed 127.0.0.1:9001 --artifact $artifactId com.example.LongRunningJob" -RedirectStandardOutput data/job.log -RedirectStandardError data/job_err.log -WindowStyle Hidden -PassThru

Write-Host "Waiting for job to start on a worker..."
$workerNode = 0
$workerPid = 0
while ($true) {
    if (Test-Path data/node1.log) { if ((Get-Content data/node1.log) -match "LongRunningJob starting on node") { $workerNode = 1; $workerPid = $p1.Id; break } }
    if (Test-Path data/node2.log) { if ((Get-Content data/node2.log) -match "LongRunningJob starting on node") { $workerNode = 2; $workerPid = $p2.Id; break } }
    if (Test-Path data/node3.log) { if ((Get-Content data/node3.log) -match "LongRunningJob starting on node") { $workerNode = 3; $workerPid = $p3.Id; break } }
    Start-Sleep -Seconds 1
}

Write-Host "Job started on Node $workerNode (PID $workerPid). Letting it run for 5 seconds to capture state..."
Start-Sleep -Seconds 5

Write-Host "Killing Node $workerNode to trigger migration!"
Stop-Process -Id $workerPid -Force

Write-Host "Waiting for job to complete..."
$jobProc.WaitForExit()

Write-Host "Job completed with exit code $($jobProc.ExitCode)."
Write-Host "CLI Output:"
Get-Content data/job.log

Stop-Process -Id $p1.Id -Force -ErrorAction SilentlyContinue
Stop-Process -Id $p2.Id -Force -ErrorAction SilentlyContinue
Stop-Process -Id $p3.Id -Force -ErrorAction SilentlyContinue

Write-Host "Migration test done."
