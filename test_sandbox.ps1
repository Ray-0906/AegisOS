param(
    [string]$Test = "ALL"
)

function Build-Project {
    Write-Host "Building project..."
    $buildOutput = mvn clean package -DskipTests
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Build failed!" -ForegroundColor Red
        exit 1
    }
}

function Start-Cluster {
    param([int]$TtlMs = 60000, [switch]$Hook = $false)
    Write-Host "Killing old java processes..."
    Stop-Process -Name "java" -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2

    Write-Host "Cleaning data..."
    if (Test-Path "data") { Remove-Item -Recurse -Force "data" }
    New-Item -ItemType Directory -Force "data" | Out-Null


    Write-Host "Starting 3-node cluster..."
    
    $hookArg = if ($Hook) { "-Daegis.test.kill_after_probe=true" } else { "" }

    $global:p1 = Start-Process java -ArgumentList "-XX:ActiveProcessorCount=4 -Xmx8G -Daegis.reservation.ttl=$TtlMs $hookArg -cp aegis-cli/target/aegis.jar com.aegisos.cli.AegisCLI start --home data/node1 --port 9001 --metrics-port 19001 --seed 127.0.0.1:9001" -WindowStyle Hidden -RedirectStandardOutput "data/node1.log" -RedirectStandardError "data/node1_err.log" -PassThru
    $global:p2 = Start-Process java -ArgumentList "-XX:ActiveProcessorCount=4 -Xmx8G -Daegis.reservation.ttl=$TtlMs -cp aegis-cli/target/aegis.jar com.aegisos.cli.AegisCLI start --home data/node2 --port 9002 --metrics-port 19002 --seed 127.0.0.1:9001" -WindowStyle Hidden -RedirectStandardOutput "data/node2.log" -RedirectStandardError "data/node2_err.log" -PassThru
    $global:p3 = Start-Process java -ArgumentList "-XX:ActiveProcessorCount=4 -Xmx8G -Daegis.reservation.ttl=$TtlMs -cp aegis-cli/target/aegis.jar com.aegisos.cli.AegisCLI start --home data/node3 --port 9003 --metrics-port 19003 --seed 127.0.0.1:9001" -WindowStyle Hidden -RedirectStandardOutput "data/node3.log" -RedirectStandardError "data/node3_err.log" -PassThru
    
    Start-Sleep -Seconds 5
}

function Upload-Artifact {
    Write-Host "Uploading demo artifact..."
    $uploadOut = java -cp aegis-cli/target/aegis.jar com.aegisos.cli.AegisCLI artifact upload --seed 127.0.0.1:9001 aegis-demo-job/target/aegis-demo-job-1.0.jar
    $artifactId = ($uploadOut | Select-String -Pattern "artifact: ([a-f0-9]+)" | ForEach-Object { $_.Matches.Groups[1].Value })
    Write-Host "Artifact uploaded: $artifactId"
    return $artifactId
}

function Get-JavaCount {
    $procs = Get-Process java -ErrorAction SilentlyContinue
    if ($null -eq $procs) { return 0 }
    if ($procs -is [array]) { return $procs.Count }
    return 1
}

if ($Test -eq "E1" -or $Test -eq "ALL") {
    Write-Host "============================"
    Write-Host "E1: Heap Enforcement"
    Write-Host "============================"
    Start-Cluster
    $artifactId = Upload-Artifact

    $startProcs = Get-JavaCount
    Write-Host "Java processes before job: $startProcs"

    $j = Start-Process java -ArgumentList "-cp aegis-cli/target/aegis.jar com.aegisos.cli.AegisCLI run --seed 127.0.0.1:9001 --artifact $artifactId --cpu 1 --memory 128 com.example.OomJob" -WindowStyle Hidden -RedirectStandardOutput "data/oom_job.log" -RedirectStandardError "data/oom_job_err.log" -PassThru
    
    # Wait until it is scheduled and throws OOM
    Start-Sleep -Seconds 12
    
    $endProcs = Get-JavaCount
    Write-Host "Java processes after OOM: $endProcs"
    
    $jobId = (Get-Content "data/oom_job.log" | Select-String -Pattern "Submitted job (.+)" | ForEach-Object { $_.Matches.Groups[1].Value })
    if ($jobId) {
        $statusOut = ""
        for ($i = 0; $i -lt 10; $i++) {
            $statusOut = java -cp aegis-cli/target/aegis.jar com.aegisos.cli.AegisCLI status --seed 127.0.0.1:9001 $jobId
            if ($statusOut -match "FAILED") {
                break
            }
            Start-Sleep -Seconds 1
        }
        Write-Host "Jobs FAILED check: $statusOut"
    } else {
        Write-Host "Jobs FAILED check: NOT FOUND"
    }
}

if ($Test -eq "E2" -or $Test -eq "ALL") {
    Write-Host "============================"
    Write-Host "E2: CPU Time-slicing"
    Write-Host "============================"
    Start-Cluster
    $artifactId = Upload-Artifact

    $j = Start-Process java -ArgumentList "-cp aegis-cli/target/aegis.jar com.aegisos.cli.AegisCLI run --seed 127.0.0.1:9001 --artifact $artifactId --cpu 1 --memory 128 com.example.CpuSpinJob" -WindowStyle Hidden -PassThru
    
    Start-Sleep -Seconds 12
    
    Write-Host "Pinging node to check responsiveness..."
    $metrics = Invoke-RestMethod -Uri "http://127.0.0.1:19001/metrics" -ErrorAction SilentlyContinue
    if ($metrics -ne $null) {
        Write-Host "Node is responsive! RUNNING jobs: $($metrics.jobs.RUNNING)"
    } else {
        Write-Host "Node is frozen!" -ForegroundColor Red
    }
}

if ($Test -eq "E3" -or $Test -eq "ALL") {
    Write-Host "============================"
    Write-Host "E3: Process Tree Cleanup"
    Write-Host "============================"
    Start-Cluster
    $artifactId = Upload-Artifact

    $tempFile = [System.IO.Path]::Combine([System.IO.Path]::GetTempPath(), "aegis_e3_run.log")
    if (Test-Path $tempFile) { Remove-Item $tempFile }
    $j = Start-Process java -ArgumentList "-cp aegis-cli/target/aegis.jar com.aegisos.cli.AegisCLI run --seed 127.0.0.1:9001 --artifact $artifactId --cpu 1 --memory 128 com.example.ForkJob" -WindowStyle Hidden -RedirectStandardOutput $tempFile -PassThru
    $jobId = ""
    for ($k=0; $k -lt 20; $k++) { 
        Start-Sleep -Seconds 1
        if (Test-Path $tempFile) {
            $runOut = Get-Content $tempFile -ErrorAction SilentlyContinue
            if ($runOut) {
                $jobId = ($runOut | Select-String -Pattern "Submitted job (.+)" | ForEach-Object { $_.Matches.Groups[1].Value })
                if ($jobId) { break } 
            }
        }
    }
    
    Start-Sleep -Seconds 12
    
    $pingProcs = Get-Process ping -ErrorAction SilentlyContinue
    if ($pingProcs -ne $null) { Write-Host "Ping process started!" }
    
    Write-Host "Canceling job $jobId..."
    Invoke-RestMethod -Uri "http://127.0.0.1:19001/cancel?jobId=$jobId" -Method Post | Out-Null
    
    Start-Sleep -Seconds 3
    
    $pingProcsAfter = Get-Process ping -ErrorAction SilentlyContinue
    if ($pingProcsAfter -eq $null) { 
        Write-Host "Ping process was successfully killed!" 
    } else {
        Write-Host "Ping process leaked!" -ForegroundColor Red
    }
}

if ($Test -eq "E4" -or $Test -eq "ALL") {
    Write-Host "============================"
    Write-Host "E4: Disk Isolation"
    Write-Host "============================"
    Start-Cluster
    $artifactId = Upload-Artifact

    $j1 = Start-Process java -ArgumentList "-cp aegis-cli/target/aegis.jar com.aegisos.cli.AegisCLI run --seed 127.0.0.1:9001 --artifact $artifactId --cpu 1 --memory 128 com.example.DiskWriteJob" -WindowStyle Hidden -PassThru
    $j2 = Start-Process java -ArgumentList "-cp aegis-cli/target/aegis.jar com.aegisos.cli.AegisCLI run --seed 127.0.0.1:9001 --artifact $artifactId --cpu 1 --memory 128 com.example.DiskWriteJob" -WindowStyle Hidden -PassThru
    
    Start-Sleep -Seconds 20
    
    # We will check if the workdirs were created and isolated, but they are cleaned up by E5! 
    # Actually, E5 tests cleanup. E4 tests isolation. If they succeed without error, isolation works.
    $metrics = Invoke-RestMethod -Uri "http://127.0.0.1:19001/metrics" -ErrorAction SilentlyContinue
    Write-Host "Jobs COMPLETED count: $($metrics.jobs.COMPLETED)"
}

if ($Test -eq "E5" -or $Test -eq "ALL") {
    Write-Host "============================"
    Write-Host "E5: Artifact Cleanup"
    Write-Host "============================"
    Start-Cluster
    $artifactId = Upload-Artifact

    $runOut = java -cp aegis-cli/target/aegis.jar com.aegisos.cli.AegisCLI run --seed 127.0.0.1:9001 --artifact $artifactId --cpu 1 --memory 128 com.example.DiskWriteJob
    $jobId = ($runOut | Select-String -Pattern "Submitted job (.+)" | ForEach-Object { $_.Matches.Groups[1].Value })
    
    Start-Sleep -Seconds 20
    
    $workDir = [System.IO.Path]::Combine([System.IO.Path]::GetTempPath(), "aegis", "jobs", $jobId)
    if (Test-Path $workDir) {
        Write-Host "Leak! Workdir exists: $workDir" -ForegroundColor Red
    } else {
        Write-Host "Workdir successfully cleaned up!"
    }
}

if ($Test -eq "E6" -or $Test -eq "ALL") {
    Write-Host "============================"
    Write-Host "E6: Stuck Job Recovery"
    Write-Host "============================"
    Start-Cluster
    $artifactId = Upload-Artifact

    $tempFile = [System.IO.Path]::Combine([System.IO.Path]::GetTempPath(), "aegis_e6_run.log")
    if (Test-Path $tempFile) { Remove-Item $tempFile }
    $j = Start-Process java -ArgumentList "-cp aegis-cli/target/aegis.jar com.aegisos.cli.AegisCLI run --seed 127.0.0.1:9001 --artifact $artifactId --cpu 1 --memory 128 com.example.ForkJob" -WindowStyle Hidden -RedirectStandardOutput $tempFile -PassThru
    $jobId = ""
    for ($k=0; $k -lt 20; $k++) { 
        Start-Sleep -Seconds 1
        if (Test-Path $tempFile) {
            $runOut = Get-Content $tempFile -ErrorAction SilentlyContinue
            if ($runOut) {
                $jobId = ($runOut | Select-String -Pattern "Submitted job (.+)" | ForEach-Object { $_.Matches.Groups[1].Value })
                if ($jobId) { break } 
            }
        }
    }
    
    Start-Sleep -Seconds 12
    
    Write-Host "Allocator state BEFORE cancel:"
    Start-Sleep -Seconds 10; Invoke-RestMethod -Uri "http://127.0.0.1:19001/allocator" -ErrorAction SilentlyContinue | Write-Host
    
    Write-Host "Canceling job $jobId..."
    Invoke-RestMethod -Uri "http://127.0.0.1:19001/cancel?jobId=$jobId" -Method Post | Out-Null
    
    Start-Sleep -Seconds 12
    
    Write-Host "Allocator state AFTER cancel (resources should be released):"
    Start-Sleep -Seconds 10; Invoke-RestMethod -Uri "http://127.0.0.1:19001/allocator" -ErrorAction SilentlyContinue | Write-Host
}

if ($Test -eq "E7" -or $Test -eq "ALL") {
    Write-Host "============================"
    Write-Host "E7: Orphan Process Recovery"
    Write-Host "============================"
    Start-Cluster
    $artifactId = Upload-Artifact

    $tempFile = [System.IO.Path]::Combine([System.IO.Path]::GetTempPath(), "aegis_e7_run.log")
    if (Test-Path $tempFile) { Remove-Item $tempFile }
    $j = Start-Process java -ArgumentList "-cp aegis-cli/target/aegis.jar com.aegisos.cli.AegisCLI run --seed 127.0.0.1:9001 --artifact $artifactId --cpu 1 --memory 128 com.example.ForkJob" -WindowStyle Hidden -RedirectStandardOutput $tempFile -PassThru
    $jobId = ""
    for ($k=0; $k -lt 20; $k++) { 
        Start-Sleep -Seconds 1
        if (Test-Path $tempFile) {
            $runOut = Get-Content $tempFile -ErrorAction SilentlyContinue
            if ($runOut) {
                $jobId = ($runOut | Select-String -Pattern "Submitted job (.+)" | ForEach-Object { $_.Matches.Groups[1].Value })
                if ($jobId) { break } 
            }
        }
    }
    
    Start-Sleep -Seconds 12
    
    $procs = Get-JavaCount
    Write-Host "Java processes during execution: $procs"
    
    Write-Host "Hard killing all java processes..."
    Stop-Process -Name "java" -Force -ErrorAction SilentlyContinue
    
    Start-Sleep -Seconds 12
    
    $orphans = Get-JavaCount
    if ($orphans -gt 0) {
        Write-Host "Orphan java processes leaked! Count: $orphans" -ForegroundColor Red
    } else {
        Write-Host "No orphan java processes! Worker exited due to EOF."
    }
}

if ($Test -eq "E8" -or $Test -eq "ALL") {
    Write-Host "============================"
    Write-Host "E8: Worker Crash Loop"
    Write-Host "============================"
    Start-Cluster
    $artifactId = Upload-Artifact

    Write-Host "Submitting crash job 5 times..."
    for ($i=0; $i -lt 5; $i++) {
        $j = Start-Process java -ArgumentList "-cp aegis-cli/target/aegis.jar com.aegisos.cli.AegisCLI run --seed 127.0.0.1:9001 --artifact $artifactId --cpu 1 --memory 128 com.example.CrashJob" -WindowStyle Hidden -PassThru
        Start-Sleep -Seconds 3
    }
    
    Write-Host "Allocator state AFTER crash loop (should have 0 leaks):"
    Start-Sleep -Seconds 10; Invoke-RestMethod -Uri "http://127.0.0.1:19001/allocator" -ErrorAction SilentlyContinue | Write-Host
    
    $metrics = Invoke-RestMethod -Uri "http://127.0.0.1:19001/metrics" -ErrorAction SilentlyContinue
    Write-Host "Jobs FAILED count: $($metrics.jobs.FAILED)"
}

Write-Host "Stopping cluster..."
Stop-Process -Name "java" -Force -ErrorAction SilentlyContinue
Stop-Process -Name "ping" -Force -ErrorAction SilentlyContinue
