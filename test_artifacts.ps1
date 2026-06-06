$ErrorActionPreference = "Stop"

$cp = "aegis-cli/target/aegis.jar"

function Start-Cluster {
    Write-Host "Killing old java processes..."
    Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force
    Start-Sleep -Seconds 2

    Write-Host "Cleaning data..."
    if (Test-Path "data") { Remove-Item -Recurse -Force "data" }
    New-Item -ItemType Directory -Path "data" | Out-Null

    Write-Host "Starting 3-node cluster..."
    $global:p1 = Start-Process java -ArgumentList "-cp `"$cp`" com.aegisos.cli.AegisCLI start --home data/node1 --port 9001 --seed 127.0.0.1:9001" -RedirectStandardOutput "data/node1.log" -WindowStyle Hidden -PassThru
    $global:p2 = Start-Process java -ArgumentList "-cp `"$cp`" com.aegisos.cli.AegisCLI start --home data/node2 --port 9002 --seed 127.0.0.1:9001" -RedirectStandardOutput "data/node2.log" -WindowStyle Hidden -PassThru
    $global:p3 = Start-Process java -ArgumentList "-cp `"$cp`" com.aegisos.cli.AegisCLI start --home data/node3 --port 9003 --seed 127.0.0.1:9001" -RedirectStandardOutput "data/node3.log" -WindowStyle Hidden -PassThru
    
    Start-Sleep -Seconds 10
}

function Stop-Cluster {
    Write-Host "Stopping cluster..."
    Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force
}

function Upload-Artifact([string]$JarPath) {
    $out = & java -cp $cp com.aegisos.cli.AegisCLI artifact upload --seed 127.0.0.1:9001 $JarPath
    $sha = ""
    foreach ($line in $out) {
        if ($line -match "artifact: ([a-f0-9]{64})") { $sha = $matches[1] }
        elseif ($line -match "Artifact already uploaded: ([a-f0-9]{64})") { $sha = $matches[1] }
    }
    if ($sha -eq "") { throw "Failed to upload $JarPath. Output: $out" }
    return $sha
}

function Run-Job([string]$ArtifactId, [string]$ClassName, [string]$OutFile) {
    $proc = Start-Process java -ArgumentList "-cp `"$cp`" com.aegisos.cli.AegisCLI run --seed 127.0.0.1:9001 --artifact $ArtifactId $ClassName" -RedirectStandardOutput $OutFile -WindowStyle Hidden -PassThru
    return $proc
}

Start-Cluster

Write-Host "============================"
Write-Host "Test A: Cache Rehydration"
Write-Host "============================"
$shaA = Upload-Artifact "build_artifacts/ArtifactA.jar"
$j1 = Run-Job $shaA "com.example.JobA" "data/testA_1.log"
$j1.WaitForExit(30000)
if (-not (Select-String -Quiet "Result: true" "data/testA_1.log")) { throw "Test A initial run failed!" }

Write-Host "Deleting local caches for $shaA..."
Get-ChildItem -Path "data/node*/artifacts/$shaA.*" -ErrorAction SilentlyContinue | Remove-Item -Force

$j2 = Run-Job $shaA "com.example.JobA" "data/testA_2.log"
$j2.WaitForExit(30000)
if (-not (Select-String -Quiet "Result: true" "data/testA_2.log")) { throw "Test A rehydrated run failed!" }
$missCount = @(Select-String "CACHE MISS: $shaA" data/*.log).Count
if ($missCount -lt 1) { throw "Test A failed: No CACHE MISS logged, did it really rehydrate?" }
Write-Host "Test A PASSED"

Write-Host "============================"
Write-Host "Test B: Restart Persistence"
Write-Host "============================"
$shaB = Upload-Artifact "build_artifacts/ArtifactB.jar"
$j3 = Run-Job $shaB "com.example.JobB" "data/testB_1.log"
$j3.WaitForExit(30000)
Stop-Cluster
Start-Sleep -Seconds 2

# Restart cluster with same data
$global:p1 = Start-Process java -ArgumentList "-cp `"$cp`" com.aegisos.cli.AegisCLI start --home data/node1 --port 9001 --seed 127.0.0.1:9001" -RedirectStandardOutput "data/node1_b.log" -WindowStyle Hidden -PassThru
$global:p2 = Start-Process java -ArgumentList "-cp `"$cp`" com.aegisos.cli.AegisCLI start --home data/node2 --port 9002 --seed 127.0.0.1:9001" -RedirectStandardOutput "data/node2_b.log" -WindowStyle Hidden -PassThru
$global:p3 = Start-Process java -ArgumentList "-cp `"$cp`" com.aegisos.cli.AegisCLI start --home data/node3 --port 9003 --seed 127.0.0.1:9001" -RedirectStandardOutput "data/node3_b.log" -WindowStyle Hidden -PassThru
Start-Sleep -Seconds 10

$j4 = Run-Job $shaB "com.example.JobB" "data/testB_2.log"
$j4.WaitForExit(30000)
if (-not (Select-String -Quiet "Result: true" "data/testB_2.log")) { throw "Test B run after restart failed!" }
Write-Host "Test B PASSED"

Write-Host "============================"
Write-Host "Test C: Cold-Node Execution"
Write-Host "============================"
$shaC = Upload-Artifact "build_artifacts/ArtifactC.jar"
$j5 = Run-Job $shaC "com.example.JobC" "data/testC_1.log"
$j5.WaitForExit(30000)
# Instead of killing a specific node, we wipe caches on ALL nodes to force cold hydration on next run
Write-Host "Wiping caches..."
Get-ChildItem -Path "data/node*/artifacts/$shaC.*" -ErrorAction SilentlyContinue | Remove-Item -Force
$j6 = Run-Job $shaC "com.example.JobC" "data/testC_2.log"
$j6.WaitForExit(30000)
if (-not (Select-String -Quiet "Result: true" "data/testC_2.log")) { throw "Test C cold run failed!" }
Write-Host "Test C PASSED"

Write-Host "============================"
Write-Host "Test D: Versioning Update"
Write-Host "============================"
$shaDv1 = Upload-Artifact "build_artifacts/ArtifactD_v1.jar"
$j7 = Run-Job $shaDv1 "com.example.JobD" "data/testD_v1.log"
$j7.WaitForExit(30000)
$shaDv2 = Upload-Artifact "build_artifacts/ArtifactD_v2.jar"
$j8 = Run-Job $shaDv2 "com.example.JobD" "data/testD_v2.log"
$j8.WaitForExit(30000)

$v1Logs = Get-Content data/node*.log -ErrorAction SilentlyContinue | Select-String "ArtifactD version 1"
$v2Logs = Get-Content data/node*.log -ErrorAction SilentlyContinue | Select-String "ArtifactD version 2"
if ($v1Logs.Count -eq 0 -or $v2Logs.Count -eq 0) { throw "Test D failed: Missing version execution logs." }
Write-Host "Test D PASSED"

Write-Host "============================"
Write-Host "Test E: Concurrent ClassLoader Isolation"
Write-Host "============================"
$shaE1 = Upload-Artifact "build_artifacts/ArtifactE_1.jar"
$shaE2 = Upload-Artifact "build_artifacts/ArtifactE_2.jar"
$j9 = Run-Job $shaE1 "com.example.JobE" "data/testE_1.log"
$j10 = Run-Job $shaE2 "com.example.JobE" "data/testE_2.log"
$j9.WaitForExit(30000)
$j10.WaitForExit(30000)

$e1Count = @(Get-Content data/node*.log -ErrorAction SilentlyContinue | Select-String "JobE finishing with: Impl-1").Count
$e2Count = @(Get-Content data/node*.log -ErrorAction SilentlyContinue | Select-String "JobE finishing with: Impl-2").Count
if ($e1Count -eq 0 -or $e2Count -eq 0) { throw "Test E failed: Missing concurrent execution results." }
Write-Host "Test E PASSED"

Write-Host "============================"
Write-Host "Test F: Artifact Deletion During Execution"
Write-Host "============================"
$shaF = Upload-Artifact "build_artifacts/ArtifactF.jar"
$j11 = Run-Job $shaF "com.example.JobF" "data/testF.log"
Start-Sleep -Seconds 2
Write-Host "Deleting local caches while running..."
Get-ChildItem -Path "data/node*/artifacts/$shaF.*" -ErrorAction SilentlyContinue | Remove-Item -Force
$j11.WaitForExit(30000)
if (-not (Select-String -Quiet "Result: true" "data/testF.log")) { throw "Test F failed: Job crashed after cache deletion." }
Write-Host "Test F PASSED"

Write-Host "============================"
Write-Host "Test G: Concurrent Version Race"
Write-Host "============================"
$shaG1 = Upload-Artifact "build_artifacts/ArtifactG_v1.jar"
$shaG2 = Upload-Artifact "build_artifacts/ArtifactG_v2.jar"
$j12 = Run-Job $shaG1 "com.example.JobG" "data/testG_1.log"
$j13 = Run-Job $shaG2 "com.example.JobG" "data/testG_2.log"
$j12.WaitForExit(30000)
$j13.WaitForExit(30000)
$g1Count = @(Get-Content data/node*.log -ErrorAction SilentlyContinue | Select-String "ArtifactG v1 completing").Count
$g2Count = @(Get-Content data/node*.log -ErrorAction SilentlyContinue | Select-String "ArtifactG v2 completing").Count
if ($g1Count -eq 0 -or $g2Count -eq 0) { throw "Test G failed: Concurrent version race resulted in contamination." }
Write-Host "Test G PASSED"

Stop-Cluster
Write-Host "ALL TESTS PASSED SUCCESSFULLY."
