$passes = 0
$failures = 0
$durations = @()
$failDetails = @()

Write-Host "Running Phase5Test.schedulesManyJobs x20..." -ForegroundColor Cyan
Write-Host ""

for ($i = 1; $i -le 20; $i++) {
    $start = Get-Date
    $out = mvn "-pl" "aegis-test-cluster" "test" `
        "-Dtest=Phase5Test#schedulesManyJobs" `
        "-Dsurefire.failIfNoSpecifiedTests=false" `
        "--no-transfer-progress" `
        "-q" 2>&1
    $exit = $LASTEXITCODE
    $elapsed = [int]((Get-Date) - $start).TotalMilliseconds

    $durations += $elapsed

    if ($exit -eq 0) {
        $passes++
        $symbol = "[PASS]"
        $color  = "Green"
    } else {
        $failures++
        $symbol = "[FAIL]"
        $color  = "Red"
        # capture the error line
        $errLine = $out | Select-String "ERROR|Exception" | Select-Object -First 1
        $failDetails += "Run $i : $errLine"
    }

    Write-Host ("{0} Run {1,2}/20  {2,5} ms" -f $symbol, $i, $elapsed) -ForegroundColor $color
}

$fastest = ($durations | Measure-Object -Minimum).Minimum
$slowest = ($durations | Measure-Object -Maximum).Maximum
$avg     = [int](($durations | Measure-Object -Average).Average)

Write-Host ""
Write-Host "================================" -ForegroundColor Cyan
Write-Host "  Passes  : $passes / 20"
Write-Host "  Failures: $failures / 20"
Write-Host "  Fastest : $fastest ms"
Write-Host "  Slowest : $slowest ms"
Write-Host "  Average : $avg ms"
Write-Host "================================" -ForegroundColor Cyan

if ($failDetails.Count -gt 0) {
    Write-Host ""
    Write-Host "Failure details:" -ForegroundColor Red
    $failDetails | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
}
