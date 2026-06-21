$logFile = "C:\Users\astra\Desktop\AegisOS\stage3_staircase.log"
Clear-Content $logFile -ErrorAction SilentlyContinue
$tests = "StaleCheckpointFenceTest,DuplicateExecutionPreventionTest,StaleQueuedExecutionTest,Phase6Test#runningJobSurvivesNodeDeath"

for ($i=1; $i -le 400; $i++) {
    Write-Host "--- STAGE 3 ITERATION $i ---"
    $cmd = "mvn test -pl aegis-test-cluster -Dtest=$tests"
    cmd /c $cmd | Tee-Object -Append -FilePath $logFile
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Stage 3 Failed on Iteration $i"
        exit 1
    }
}
Write-Host "Stage 3 Passed"
exit 0
