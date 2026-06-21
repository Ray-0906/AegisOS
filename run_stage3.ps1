$logFile = "C:\Users\astra\Desktop\AegisOS\stage3_staircase.log"
Clear-Content $logFile -ErrorAction SilentlyContinue

function Run-Test {
    param(
        [string]$TestName,
        [int]$Iterations
    )
    for ($i=1; $i -le $Iterations; $i++) {
        Write-Host "--- CERTIFICATION PACK: $TestName ITERATION $i ---"
        $cmd = "mvn test -pl aegis-test-cluster -Dtest=$TestName"
        cmd /c $cmd | Tee-Object -Append -FilePath $logFile
        if ($LASTEXITCODE -ne 0) {
            Write-Host "Certification Pack Failed on $TestName Iteration $i"
            exit 1
        }
    }
}

Run-Test -TestName "StaleCheckpointFenceTest" -Iterations 50
Run-Test -TestName "DuplicateExecutionPreventionTest" -Iterations 25
Run-Test -TestName "StaleQueuedExecutionTest" -Iterations 25
Run-Test -TestName "Phase6Test#runningJobSurvivesNodeDeath" -Iterations 25

Write-Host "Certification Pack Passed"
exit 0
