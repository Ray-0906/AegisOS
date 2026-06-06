param (
    [string]$Test = "S1"
)
$ErrorActionPreference = "Stop"
$cp = "aegis-cli/target/aegis.jar"
$demoJar = "aegis-demo-job/target/aegis-demo-job-1.0.jar"

function Start-Cluster {
    Write-Host "Killing old java processes..."
    Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force
    Start-Sleep -Seconds 2

    Write-Host "Cleaning data..."
    if (Test-Path "data") { Remove-Item -Recurse -Force "data" }
    New-Item -ItemType Directory -Path "data" | Out-Null

    Write-Host "Starting 3-node cluster with 4 CPU cores and 8GB RAM each..."
    # We constrain the nodes using ActiveProcessorCount=4
    $global:p1 = Start-Process java -ArgumentList "-XX:ActiveProcessorCount=4 -Xmx8G -cp `"$cp`" com.aegisos.cli.AegisCLI start --home data/node1 --port 9001 --seed 127.0.0.1:9001" -RedirectStandardOutput "data/node1.log" -WindowStyle Hidden -PassThru
    $global:p2 = Start-Process java -ArgumentList "-XX:ActiveProcessorCount=4 -Xmx8G -cp `"$cp`" com.aegisos.cli.AegisCLI start --home data/node2 --port 9002 --seed 127.0.0.1:9001" -RedirectStandardOutput "data/node2.log" -WindowStyle Hidden -PassThru
    $global:p3 = Start-Process java -ArgumentList "-XX:ActiveProcessorCount=4 -Xmx8G -cp `"$cp`" com.aegisos.cli.AegisCLI start --home data/node3 --port 9003 --seed 127.0.0.1:9001" -RedirectStandardOutput "data/node3.log" -WindowStyle Hidden -PassThru
    
    Start-Sleep -Seconds 15
}

function Restart-Cluster {
    Write-Host "Killing old java processes..."
    Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force
    Start-Sleep -Seconds 2

    Write-Host "Restarting 3-node cluster WITHOUT wiping data..."
    $global:p1 = Start-Process java -ArgumentList "-XX:ActiveProcessorCount=4 -Xmx8G -cp `"$cp`" com.aegisos.cli.AegisCLI start --home data/node1 --port 9001 --seed 127.0.0.1:9001" -RedirectStandardOutput "data/node1.log" -WindowStyle Hidden -PassThru
    $global:p2 = Start-Process java -ArgumentList "-XX:ActiveProcessorCount=4 -Xmx8G -cp `"$cp`" com.aegisos.cli.AegisCLI start --home data/node2 --port 9002 --seed 127.0.0.1:9001" -RedirectStandardOutput "data/node2.log" -WindowStyle Hidden -PassThru
    $global:p3 = Start-Process java -ArgumentList "-XX:ActiveProcessorCount=4 -Xmx8G -cp `"$cp`" com.aegisos.cli.AegisCLI start --home data/node3 --port 9003 --seed 127.0.0.1:9001" -RedirectStandardOutput "data/node3.log" -WindowStyle Hidden -PassThru
    
    Start-Sleep -Seconds 15
}

function Stop-Cluster {
    Write-Host "Stopping cluster..."
    Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force
}

Start-Cluster

Write-Host "Uploading demo artifact..."
$uploadOut = java -cp $cp com.aegisos.cli.AegisCLI artifact upload --seed 127.0.0.1:9001 $demoJar
$artifactId = ($uploadOut | Select-String -Pattern "artifact: ([a-f0-9]+)" | ForEach-Object { $_.Matches.Groups[1].Value })
Write-Host "Artifact uploaded: $artifactId"

if ($Test -eq "S1" -or $Test -eq "ALL") {
    Write-Host "============================"
    Write-Host "S1: Reservation Safety"
    Write-Host "============================"
    
    $j1 = Start-Process java -ArgumentList "-cp `"$cp`" com.aegisos.cli.AegisCLI run --seed 127.0.0.1:9001 --artifact $artifactId --cpu 1 --memory 8000 com.example.LongRunningJob" -RedirectStandardOutput "data/s1_j1.log" -WindowStyle Hidden -PassThru
    $j2 = Start-Process java -ArgumentList "-cp `"$cp`" com.aegisos.cli.AegisCLI run --seed 127.0.0.1:9001 --artifact $artifactId --cpu 1 --memory 8000 com.example.LongRunningJob" -RedirectStandardOutput "data/s1_j2.log" -WindowStyle Hidden -PassThru
    $j3 = Start-Process java -ArgumentList "-cp `"$cp`" com.aegisos.cli.AegisCLI run --seed 127.0.0.1:9001 --artifact $artifactId --cpu 1 --memory 8000 com.example.LongRunningJob" -RedirectStandardOutput "data/s1_j3.log" -WindowStyle Hidden -PassThru
    $j4 = Start-Process java -ArgumentList "-cp `"$cp`" com.aegisos.cli.AegisCLI run --seed 127.0.0.1:9001 --artifact $artifactId --cpu 1 --memory 8000 com.example.LongRunningJob" -RedirectStandardOutput "data/s1_j4.log" -WindowStyle Hidden -PassThru

    Write-Host "Waiting 15 seconds for jobs to complete..."
    Start-Sleep -Seconds 15

    $j1log = Get-Content "data/s1_j1.log" -Raw -ErrorAction SilentlyContinue
    $j2log = Get-Content "data/s1_j2.log" -Raw -ErrorAction SilentlyContinue
    $j3log = Get-Content "data/s1_j3.log" -Raw -ErrorAction SilentlyContinue
    $j4log = Get-Content "data/s1_j4.log" -Raw -ErrorAction SilentlyContinue

    if ($j4log -match "Result:") {
        Write-Host "FAIL: Job 4 succeeded immediately, meaning memory was oversubscribed!" -ForegroundColor Red
    } else {
        Write-Host "PASS (tentative): Job 4 was prevented from running." -ForegroundColor Green
    }
}

if ($Test -eq "S3" -or $Test -eq "ALL") {
    Write-Host "============================"
    Write-Host "S3: Concurrent Placement Race"
    Write-Host "============================"
    $jobs = @()
    for ($i = 1; $i -le 10; $i++) {
        $jobs += Start-Process java -ArgumentList "-cp `"$cp`" com.aegisos.cli.AegisCLI run --seed 127.0.0.1:9001 --artifact $artifactId --cpu 2 --memory 100 com.example.LongRunningJob" -RedirectStandardOutput "data/s3_j$i.log" -WindowStyle Hidden -PassThru
    }
    for ($k = 1; $k -le 5; $k++) {
        Start-Sleep -Seconds 2
        Write-Host "Allocator Status (Attempt $k):"
        java -cp $cp com.aegisos.cli.AegisCLI allocator status --api-port 19001
        java -cp $cp com.aegisos.cli.AegisCLI allocator status --api-port 19002
        java -cp $cp com.aegisos.cli.AegisCLI allocator status --api-port 19003
    }
    
    Start-Sleep -Seconds 10
}

if ($Test -eq "S5" -or $Test -eq "ALL") {
    Write-Host "============================"
    Write-Host "S5: Accounting Recovery"
    Write-Host "============================"
    $j1 = Start-Process java -ArgumentList "-cp `"$cp`" com.aegisos.cli.AegisCLI run --seed 127.0.0.1:9001 --artifact $artifactId --cpu 1 --memory 8000 com.example.LongRunningJob" -WindowStyle Hidden -PassThru
    $j2 = Start-Process java -ArgumentList "-cp `"$cp`" com.aegisos.cli.AegisCLI run --seed 127.0.0.1:9001 --artifact $artifactId --cpu 1 --memory 8000 com.example.LongRunningJob" -WindowStyle Hidden -PassThru
    $j3 = Start-Process java -ArgumentList "-cp `"$cp`" com.aegisos.cli.AegisCLI run --seed 127.0.0.1:9001 --artifact $artifactId --cpu 1 --memory 8000 com.example.LongRunningJob" -WindowStyle Hidden -PassThru
    
    Start-Sleep -Seconds 10
    Write-Host "Before restart Node 1 allocations:"
    java -cp $cp com.aegisos.cli.AegisCLI allocator status --api-port 19001
    
    Write-Host "Killing cluster for restart..."
    Restart-Cluster
    
    Write-Host "Rebuilt allocations on Node 1:"
    java -cp $cp com.aegisos.cli.AegisCLI allocator status --api-port 19001
}

if ($Test -eq "S7" -or $Test -eq "ALL") {
    Write-Host "============================"
    Write-Host "S7: Queue Recovery"
    Write-Host "============================"
    for ($i = 1; $i -le 10; $i++) {
        Start-Process java -ArgumentList "-cp `"$cp`" com.aegisos.cli.AegisCLI run --seed 127.0.0.1:9001 --artifact $artifactId --cpu 1 --memory 8000 com.example.LongRunningJob" -WindowStyle Hidden -PassThru
    }
    Start-Sleep -Seconds 5
    Write-Host "Queue Status BEFORE Restart:"
    $metrics = Invoke-RestMethod -Uri "http://127.0.0.1:19001/metrics" -ErrorAction SilentlyContinue
    $totalWait = $metrics.jobs.QUEUED + $metrics.jobs.PENDING
    Write-Host "QUEUED/PENDING jobs count: $totalWait"
    
    Restart-Cluster
    
    Write-Host "Queue Status AFTER Restart:"
    $metrics = Invoke-RestMethod -Uri "http://127.0.0.1:19001/metrics" -ErrorAction SilentlyContinue
    $totalWait = $metrics.jobs.QUEUED + $metrics.jobs.PENDING
    Write-Host "QUEUED/PENDING jobs count: $totalWait"
}

if ($Test -eq "S6" -or $Test -eq "ALL") {
    Write-Host "============================"
    Write-Host "S6: Reservation Leak Test"
    Write-Host "============================"
    Stop-Cluster
    if (Test-Path "data") { Remove-Item -Recurse -Force "data" }
    New-Item -ItemType Directory -Path "data" | Out-Null
    
    Write-Host "Starting cluster with test hook enabled and 5s TTL..."
    $global:p1 = Start-Process java -ArgumentList "-XX:ActiveProcessorCount=4 -Xmx8G -Daegis.test.kill_after_probe=true -Daegis.reservation.ttl=5000 -cp `"$cp`" com.aegisos.cli.AegisCLI start --home data/node1 --port 9001 --seed 127.0.0.1:9001" -RedirectStandardOutput "data/node1.log" -WindowStyle Hidden -PassThru
    $global:p2 = Start-Process java -ArgumentList "-XX:ActiveProcessorCount=4 -Xmx8G -Daegis.test.kill_after_probe=true -Daegis.reservation.ttl=5000 -cp `"$cp`" com.aegisos.cli.AegisCLI start --home data/node2 --port 9002 --seed 127.0.0.1:9001" -RedirectStandardOutput "data/node2.log" -WindowStyle Hidden -PassThru
    $global:p3 = Start-Process java -ArgumentList "-XX:ActiveProcessorCount=4 -Xmx8G -Daegis.test.kill_after_probe=true -Daegis.reservation.ttl=5000 -cp `"$cp`" com.aegisos.cli.AegisCLI start --home data/node3 --port 9003 --seed 127.0.0.1:9001" -RedirectStandardOutput "data/node3.log" -WindowStyle Hidden -PassThru
    Start-Sleep -Seconds 15
    
    $uploadOut = java -cp $cp com.aegisos.cli.AegisCLI artifact upload --seed 127.0.0.1:9001 $demoJar
    $artifactId = ($uploadOut | Select-String -Pattern "artifact: ([a-f0-9]+)" | ForEach-Object { $_.Matches.Groups[1].Value })
    
    Write-Host "Submitting job to trigger PROBE... Leader should die."
    Start-Process java -ArgumentList "-cp `"$cp`" com.aegisos.cli.AegisCLI run --seed 127.0.0.1:9001 --artifact $artifactId --cpu 2 --memory 100 com.example.LongRunningJob" -WindowStyle Hidden -PassThru
    
    Start-Sleep -Seconds 5
    Write-Host "Checking allocator status right after leader dies (Reservation should exist):"
    java -cp $cp com.aegisos.cli.AegisCLI allocator status --api-port 19001
    java -cp $cp com.aegisos.cli.AegisCLI allocator status --api-port 19002
    java -cp $cp com.aegisos.cli.AegisCLI allocator status --api-port 19003
    
    Write-Host "Waiting 10s for 5s TTL to expire..."
    Start-Sleep -Seconds 10
    
    Write-Host "Checking allocator status (Reservation should be gone):"
    java -cp $cp com.aegisos.cli.AegisCLI allocator status --api-port 19001
    java -cp $cp com.aegisos.cli.AegisCLI allocator status --api-port 19002
    java -cp $cp com.aegisos.cli.AegisCLI allocator status --api-port 19003
}

Stop-Cluster
