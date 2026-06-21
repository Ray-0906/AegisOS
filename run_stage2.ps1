$logFile = "C:\Users\astra\Desktop\AegisOS\stage2_staircase.log"
Clear-Content $logFile -ErrorAction SilentlyContinue
$tests = "StaleCheckpointFenceTest"

for ($i=1; $i -le 100; $i++) {
    Write-Host "--- STAGE 2 ITERATION $i ---"
    $cmd = "mvn test -pl aegis-test-cluster -Dtest=$tests"
    cmd /c $cmd | Tee-Object -Append -FilePath $logFile
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Stage 2 Failed on Iteration $i"
        exit 1
    }
}
Write-Host "Stage 2 Passed"
exit 0
