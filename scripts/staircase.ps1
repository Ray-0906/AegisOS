$ErrorActionPreference = "Stop"
$test = "LongRunningCheckpointChaosTest"
$N = 100

Write-Host "Staircase: ${N} runs"
$passed = 0
$failed = 0

for ($i = 1; $i -le $N; $i++) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $logFile = "logs/run_${i}.log"
    mvn test -pl aegis-test-cluster "-Dtest=$test" > $logFile 2>&1
    $sw.Stop()

    if ($LASTEXITCODE -eq 0) {
        $passed++
        Write-Host "  Run $i pass"
        Remove-Item $logFile -Force
    } else {
        $failed++
        Write-Host "FAILED on run $i. See $logFile for details."
        break
    }
}

Write-Host "Result ${N}: Pass=$passed Fail=$failed"
if ($failed -gt 0) {
    exit 1
}
exit 0
