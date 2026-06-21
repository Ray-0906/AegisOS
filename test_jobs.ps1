Write-Host "Killing old java processes..."
Stop-Process -Name "java" -Force -ErrorAction SilentlyContinue

Write-Host "Cleaning data..."
if (Test-Path "data") { Remove-Item -Recurse -Force "data" }
New-Item -ItemType Directory -Force "data" | Out-Null

Write-Host "Starting cluster..."
$global:p1 = Start-Process java -ArgumentList "-cp aegis-cli/target/aegis.jar com.aegisos.cli.AegisCLI start --home data/node1 --port 9001 --seed 127.0.0.1:9001 --bootstrap" -WindowStyle Hidden -PassThru
$global:p2 = Start-Process java -ArgumentList "-cp aegis-cli/target/aegis.jar com.aegisos.cli.AegisCLI start --home data/node2 --port 9002 --seed 127.0.0.1:9001" -WindowStyle Hidden -PassThru
$global:p3 = Start-Process java -ArgumentList "-cp aegis-cli/target/aegis.jar com.aegisos.cli.AegisCLI start --home data/node3 --port 9003 --seed 127.0.0.1:9001" -WindowStyle Hidden -PassThru

Start-Sleep -Seconds 10

Write-Host "`n=== TESTING JOBS (DEFAULT LIST) COMMAND ==="
java -cp aegis-cli/target/aegis.jar com.aegisos.cli.AegisCLI jobs --seed 127.0.0.1:9001

Write-Host "`n=== UPLOADING ARTIFACT AND RUNNING JOB ==="
$uploadOut = java -cp aegis-cli/target/aegis.jar com.aegisos.cli.AegisCLI artifact upload --seed 127.0.0.1:9001 aegis-demo-job/target/aegis-demo-job-1.0.jar
$artifactId = ($uploadOut | Select-String -Pattern "artifact: ([a-f0-9]+)" | ForEach-Object { $_.Matches.Groups[1].Value })
if ($artifactId) {
    Write-Host "Uploaded artifact $artifactId"
    $runOut = java -cp aegis-cli/target/aegis.jar com.aegisos.cli.AegisCLI run --seed 127.0.0.1:9001 --artifact $artifactId --cpu 1 --memory 128 com.example.ForkJob
    $jobId = ($runOut | Select-String -Pattern "Submitted job (.+)" | ForEach-Object { $_.Matches.Groups[1].Value })
    
    if ($jobId) {
        Write-Host "Started job $jobId"
        Start-Sleep -Seconds 2
        Write-Host "`n=== TESTING JOBS COMMAND (SHOULD SHOW JOB) ==="
        java -cp aegis-cli/target/aegis.jar com.aegisos.cli.AegisCLI jobs --seed 127.0.0.1:9001
        
        Write-Host "`n=== TESTING STATUS COMMAND ==="
        java -cp aegis-cli/target/aegis.jar com.aegisos.cli.AegisCLI status --seed 127.0.0.1:9001 $jobId
    }
}

Write-Host "`nStopping cluster..."
Stop-Process -Name "java" -Force -ErrorAction SilentlyContinue
