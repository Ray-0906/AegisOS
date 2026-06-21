$logFile = "C:\Users\astra\Desktop\AegisOS\stage1_staircase.log"
Clear-Content $logFile -ErrorAction SilentlyContinue
$tests = "StaleCheckpointFenceTest,DuplicateExecutionPreventionTest,StaleQueuedExecutionTest,Phase6Test#runningJobSurvivesNodeDeath"

for ($i=1; $i -le 25; $i++) {
    Write-Host "--- STAGE 1 ITERATION $i ---"
    $cmd = "mvn clean test -pl aegis-test-cluster -Dtest=$tests"
    cmd /c $cmd | Tee-Object -Append -FilePath $logFile
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Stage 1 Failed on Iteration $i"
        exit 1
    }
}
Write-Host "Stage 1 Passed"
exit 0
