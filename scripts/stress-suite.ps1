# Stress Suite Runner
# Usage: .\scripts\stress-suite.ps1
# Runs every enrolled test N times and reports pass/fail.

param(
    [int]$Iterations = 100
)

$ErrorActionPreference = "Stop"

# Enrolled tests — every flaky test becomes a permanent stress test.
# Add new tests here as they are stabilized.
$tests = @(
    "com.aegisos.cluster.CorruptCheckpointRecoveryTest",
    "com.aegisos.cluster.Phase7Test"
    # Future additions after stabilization:
    # "com.aegisos.cluster.Phase8Test",
    # "com.aegisos.cluster.WorkerFailureRecoveryTest",
    # "com.aegisos.cluster.LongRunningCheckpointChaosTest"
)

$results = @{}
$overallSuccess = $true

foreach ($test in $tests) {
    $shortName = $test.Split(".")[-1]
    Write-Host "`n========================================" -ForegroundColor Cyan
    Write-Host "STRESS: $shortName x $Iterations" -ForegroundColor Cyan
    Write-Host "========================================" -ForegroundColor Cyan

    $passed = 0
    $failed = 0
    $failedOn = @()

    for ($i = 1; $i -le $Iterations; $i++) {
        Write-Host "  Run $i/$Iterations " -NoNewline
        mvn test -q -pl aegis-test-cluster "-Dtest=$test" 2>&1 | Out-Null

        if ($LASTEXITCODE -eq 0) {
            Write-Host "PASS" -ForegroundColor Green
            $passed++
        } else {
            Write-Host "FAIL" -ForegroundColor Red
            $failed++
            $failedOn += $i
            # Don't break — run all iterations to measure flake rate
        }
    }

    $results[$shortName] = @{
        Passed   = $passed
        Failed   = $failed
        FailedOn = $failedOn
        Rate     = [math]::Round(($passed / $Iterations) * 100, 1)
    }

    if ($failed -gt 0) {
        $overallSuccess = $false
    }
}

# Report
Write-Host "`n`n========================================" -ForegroundColor Cyan
Write-Host "STRESS SUITE REPORT" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ("Iterations per test: {0}" -f $Iterations)
Write-Host ""

Write-Host ("{0,-45} {1,6} {2,6} {3,8}" -f "Test", "Pass", "Fail", "Rate")
Write-Host ("{0,-45} {1,6} {2,6} {3,8}" -f "----", "----", "----", "----")

foreach ($test in $results.Keys | Sort-Object) {
    $r = $results[$test]
    $color = if ($r.Failed -eq 0) { "Green" } else { "Red" }
    Write-Host ("{0,-45} {1,6} {2,6} {3,7}%" -f $test, $r.Passed, $r.Failed, $r.Rate) -ForegroundColor $color

    if ($r.FailedOn.Count -gt 0) {
        Write-Host ("  Failed on runs: {0}" -f ($r.FailedOn -join ", ")) -ForegroundColor Yellow
    }
}

Write-Host ""
if ($overallSuccess) {
    Write-Host "RESULT: ALL TESTS PASSED $Iterations/$Iterations" -ForegroundColor Green
    exit 0
} else {
    Write-Host "RESULT: FLAKY TESTS DETECTED" -ForegroundColor Red
    exit 1
}
